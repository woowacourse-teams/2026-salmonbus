package com.gustler.backend.forecasting.domain.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Observation;
import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Previous;
import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Route;
import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Status;
import com.gustler.backend.forecasting.domain.quality.TripQualityInvestigation.BatchObservations;
import com.gustler.backend.forecasting.domain.quality.TripQualityInvestigation.Phase;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class TripQualityInvestigationTest {
    private static final Instant AT = Instant.parse("2026-09-23T00:00:00Z");
    private static final Route ROUTE = new Route(1, 7, 4, null);

    @Test
    void 이상_관측을_처음_발견하면_그_배치부터_시작점을_찾고_미설정_간격은_10분을_사용한다() {
        // given
        final QualityObservationBatch.Row anomaly = new QualityObservationBatch.Row(51, "bus", 71);
        final QualityObservationBatch batch = new QualityObservationBatch(5, 1, AT, List.of(anomaly));

        // when
        final TripQualityInvestigation investigation = TripQualityInvestigation.start(batch, anomaly, null);

        // then
        assertThat(investigation.routeVersionId()).isEqualTo(1);
        assertThat(investigation.vehicleId()).isEqualTo("bus");
        assertThat(investigation.phase()).isEqualTo(Phase.SEARCH_START);
        assertThat(investigation.cursorAt()).isEqualTo(AT);
        assertThat(investigation.cursorBatchId()).isEqualTo(5);
        assertThat(investigation.evidenceAt()).isEqualTo(AT);
        assertThat(investigation.evidenceObservationId()).isEqualTo(51);
        assertThat(investigation.anchorObservationId()).isEqualTo(51);
        assertThat(investigation.previousObservationId()).isNull();
        assertThat(investigation.boundaryCandidateObservationId()).isNull();
        assertThat(investigation.includeCursor()).isFalse();
        assertThat(investigation.canRelease()).isFalse();
        assertThat(investigation.maximumGap()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void 정상_좌석이거나_차량을_식별할_수_없는_관측은_품질_조사를_시작하지_않는다() {
        // given
        final List<QualityObservationBatch.Row> observations = List.of(
            new QualityObservationBatch.Row(51, "bus", 70),
            new QualityObservationBatch.Row(52, "bus", null),
            new QualityObservationBatch.Row(53, null, 71),
            new QualityObservationBatch.Row(54, " ", 71));
        final QualityObservationBatch batch = new QualityObservationBatch(5, 1, AT, observations);

        // when, then
        for (final QualityObservationBatch.Row observation : observations) {
            assertThatIllegalArgumentException().isThrownBy(
                () -> TripQualityInvestigation.start(batch, observation, Duration.ofMinutes(10)));
        }
    }

    @ParameterizedTest
    @EnumSource(value = Phase.class, names = {"SEARCH_START", "REPLAY"})
    void 진행_중_새_이상_관측이_들어와도_기존_조사_위치와_기준을_유지한다(final Phase phase) {
        // given
        final QualityObservationBatch.Row original = new QualityObservationBatch.Row(51, "bus", 71);
        final TripQualityInvestigation investigation = TripQualityInvestigation.start(
            new QualityObservationBatch(5, 1, AT, List.of(original)), original, Duration.ofMinutes(10));
        if (phase == Phase.REPLAY) {
            final BatchObservations anchor = new BatchObservations(5, AT,
                new Observation(51, "bus", AT, 3, 0, 71));
            investigation.searchStart(ROUTE, List.of(), Map.of(51L, anchor));
        }
        final InvestigationState before = stateOf(investigation);
        final QualityObservationBatch.Row laterAnomaly = new QualityObservationBatch.Row(61, "bus", 80);
        final QualityObservationBatch laterBatch = new QualityObservationBatch(6, 1, AT.plusSeconds(30), List.of(laterAnomaly));

        // when
        final Optional<TripQualityInvestigation> restarted = investigation.restart(
            laterBatch, laterAnomaly, Duration.ofMinutes(20));

        // then
        assertThat(restarted).isEmpty();
        assertThat(stateOf(investigation)).isEqualTo(before);
    }

    @Test
    void 완료_후_새_이상_관측이_들어오면_이전_조사를_보존하고_새_시작점_탐색을_만든다() {
        // given
        final TripQualityInvestigation completed = investigation(Phase.DONE, row(1, 1, 2, 44), true);
        final InvestigationState before = stateOf(completed);
        final QualityObservationBatch.Row anomaly = new QualityObservationBatch.Row(61, "bus", 80);
        final Instant discoveredAt = AT.plusSeconds(30);
        final QualityObservationBatch batch = new QualityObservationBatch(6, 1, discoveredAt, List.of(anomaly));
        final Duration maximumGap = Duration.ofMinutes(20);

        // when
        final TripQualityInvestigation restarted = completed.restart(batch, anomaly, maximumGap).orElseThrow();

        // then
        assertThat(restarted).isNotSameAs(completed);
        assertThat(stateOf(completed)).isEqualTo(before);
        assertThat(restarted.phase()).isEqualTo(Phase.SEARCH_START);
        assertThat(restarted.cursorAt()).isEqualTo(discoveredAt);
        assertThat(restarted.cursorBatchId()).isEqualTo(6);
        assertThat(restarted.evidenceAt()).isEqualTo(discoveredAt);
        assertThat(restarted.evidenceObservationId()).isEqualTo(61);
        assertThat(restarted.anchorObservationId()).isEqualTo(61);
        assertThat(restarted.previousObservationId()).isNull();
        assertThat(restarted.boundaryCandidateObservationId()).isNull();
        assertThat(restarted.includeCursor()).isFalse();
        assertThat(restarted.canRelease()).isFalse();
        assertThat(restarted.maximumGap()).isEqualTo(maximumGap);
    }

    @ParameterizedTest
    @EnumSource(Phase.class)
    void 다른_노선_버전이나_차량의_이상_관측으로_기존_조사를_다시_시작할_수_없다(final Phase phase) {
        // given
        final TripQualityInvestigation investigation = investigation(phase, row(1, 1, 2, 44), false);
        final InvestigationState before = stateOf(investigation);
        final QualityObservationBatch.Row sameVehicle = new QualityObservationBatch.Row(61, "bus", 71);
        final QualityObservationBatch otherRouteVersion = new QualityObservationBatch(6, 2, AT, List.of(sameVehicle));
        final QualityObservationBatch.Row otherVehicle = new QualityObservationBatch.Row(62, "other-bus", 71);
        final QualityObservationBatch sameRouteVersion = new QualityObservationBatch(6, 1, AT, List.of(otherVehicle));

        // when, then
        assertThatIllegalArgumentException().isThrownBy(
            () -> investigation.restart(otherRouteVersion, sameVehicle, Duration.ofMinutes(10)));
        assertThatIllegalArgumentException().isThrownBy(
            () -> investigation.restart(sameRouteVersion, otherVehicle, Duration.ofMinutes(10)));
        assertThat(stateOf(investigation)).isEqualTo(before);
    }

    @Test
    void 과거_조사의_단계명과_완료_표시가_다르면_완료_표시를_기준으로_재시작한다() {
        // given
        final var anomaly = new QualityObservationBatch.Row(61, "bus", 71);
        final var batch = new QualityObservationBatch(6, 1, AT.plusSeconds(30), List.of(anomaly));
        final var completed = new TripQualityInvestigation(1, "bus", AT, 5, AT, 51, Phase.REPLAY,
            51, 52L, false, true, Duration.ofMinutes(10), null, true);
        final var pending = new TripQualityInvestigation(1, "bus", AT, 5, AT, 51, Phase.DONE,
            51, 52L, false, true, Duration.ofMinutes(10), null, false);

        // when, then
        assertThat(completed.restart(batch, anomaly, Duration.ofMinutes(10))).isPresent();
        assertThat(pending.restart(batch, anomaly, Duration.ofMinutes(10))).isEmpty();
    }

    @Test
    void 차량_관측이_없는_32개_배치를_지나도_출발_후보를_보존하고_탐색_위치를_옮긴다() {
        // given
        final BatchObservations anchor = row(400, 4, 1, 44);
        final BatchObservations departure = row(410, 4, 2, 44);
        final TripQualityInvestigation investigation = new TripQualityInvestigation(
            1, "bus", anchor.at(), anchor.batch(), AT.plusSeconds(420), anchor.observation().id(), Phase.SEARCH_START,
            anchor.observation().id(), null, false, false, Duration.ofMinutes(10), departure.observation().id(), false);
        final List<BatchObservations> page = LongStream.range(0, TripQualityInvestigation.BATCH_LIMIT)
            .mapToObj(offset -> emptyBatch(399 - offset)).toList();

        // when
        investigation.searchStart(ROUTE, page, Map.of(400L, anchor, 410L, departure));

        // then
        assertThat(investigation.phase()).isEqualTo(Phase.SEARCH_START);
        assertThat(investigation.cursorBatchId()).isEqualTo(368);
        assertThat(investigation.cursorAt()).isEqualTo(AT.plusSeconds(368));
        assertThat(investigation.anchorObservationId()).isEqualTo(400);
        assertThat(investigation.boundaryCandidateObservationId()).isEqualTo(410L);
        assertThat(investigation.includeCursor()).isFalse();
    }

    @Test
    void 반복된_출발이_다음_페이지에도_있으면_첫_출발까지_찾아_재판정한다() {
        // given
        final BatchObservations anomaly = row(400, 5, 0, 71);
        final BatchObservations departure = row(399, 4, 2, 44);
        final TripQualityInvestigation investigation = investigation(Phase.SEARCH_START, anomaly, false);
        final List<BatchObservations> firstPage = new ArrayList<>();
        firstPage.add(departure);
        LongStream.range(0, TripQualityInvestigation.BATCH_LIMIT - 1)
            .mapToObj(offset -> emptyBatch(398 - offset)).forEach(firstPage::add);
        investigation.searchStart(ROUTE, firstPage, Map.of(400L, anomaly));
        assertThat(investigation.phase()).isEqualTo(Phase.SEARCH_START);
        assertThat(investigation.boundaryCandidateObservationId()).isEqualTo(399L);

        // when
        investigation.searchStart(ROUTE,
            List.of(row(367, 4, 0, 44), row(366, 4, 2, 44), row(365, 3, 2, 44)),
            Map.of(399L, departure));

        // then
        assertThat(investigation.phase()).isEqualTo(Phase.REPLAY);
        assertThat(investigation.anchorObservationId()).isEqualTo(366);
        assertThat(investigation.cursorBatchId()).isEqualTo(366);
        assertThat(investigation.includeCursor()).isTrue();
        assertThat(investigation.boundaryCandidateObservationId()).isNull();
    }

    @Test
    void 정상_편도에_도달해도_32개_배치를_모두_읽었다면_다음_페이지까지_확인한다() {
        // given
        final TripQualityInvestigation investigation = investigation(Phase.REPLAY, row(1, 1, 2, 44), false);
        final List<BatchObservations> fullPage = LongStream.rangeClosed(2, 33)
            .mapToObj(id -> row(id, 1, 2, 44)).toList();

        // when
        final List<OneWayTripAssessment> assessments = investigation.replay(ROUTE, fullPage, null);

        // then
        assertThat(investigation.canRelease()).isTrue();
        assertThat(investigation.phase()).isEqualTo(Phase.REPLAY);
        assertThat(investigation.cursorBatchId()).isEqualTo(33);

        // when
        investigation.replay(ROUTE, List.of(row(34, 2, 0, 44)), assessments.getLast().asPrevious());

        // then
        assertThat(investigation.completed()).isTrue();
        assertThat(investigation.previousObservationId()).isEqualTo(34L);
        assertThat(investigation.cursorBatchId()).isEqualTo(34);
        assertThat(investigation.includeCursor()).isFalse();
    }

    @Test
    void 제외된_편도_이후_새_관측이_없으면_조사를_완료하지_않고_기다린다() {
        // given
        final BatchObservations anomaly = row(31, 3, 0, 71);
        final TripQualityInvestigation investigation = investigation(Phase.REPLAY, anomaly, false);
        final Previous previous = new Previous(anomaly.observation(), 1, Status.EXCLUDED);

        // when
        final List<OneWayTripAssessment> assessments = investigation.replay(ROUTE, List.of(), previous);

        // then
        assertThat(assessments).isEmpty();
        assertThat(investigation.completed()).isFalse();
        assertThat(investigation.phase()).isEqualTo(Phase.REPLAY);
        assertThat(investigation.canRelease()).isFalse();
        assertThat(investigation.cursorBatchId()).isEqualTo(31);
        assertThat(investigation.previousObservationId()).isEqualTo(31L);
    }

    @Test
    void 시작점_탐색과_재판정은_각각의_단계에서만_진행한다() {
        // given
        final BatchObservations anchor = row(1, 1, 2, 44);
        final TripQualityInvestigation searching = investigation(Phase.SEARCH_START, anchor, false);
        final TripQualityInvestigation replaying = investigation(Phase.REPLAY, anchor, false);
        final TripQualityInvestigation completed = investigation(Phase.DONE, anchor, true);

        // when, then
        assertThatIllegalStateException().isThrownBy(() -> searching.replay(ROUTE, List.of(), null));
        assertThatIllegalStateException().isThrownBy(() -> replaying.searchStart(ROUTE, List.of(), Map.of()));
        assertThatIllegalStateException().isThrownBy(() -> completed.replay(ROUTE, List.of(), null));
        assertThatIllegalStateException().isThrownBy(() -> completed.searchStart(ROUTE, List.of(), Map.of()));
    }

    private static TripQualityInvestigation investigation(final Phase phase, final BatchObservations anchor,
        final boolean canRelease) {
        return new TripQualityInvestigation(1, "bus", anchor.at(), anchor.batch(), anchor.at(), anchor.observation().id(), phase,
            anchor.observation().id(), null, true, canRelease, Duration.ofMinutes(10), null, phase == Phase.DONE);
    }

    private static BatchObservations row(final long id, final int stop, final int state, final int seats) {
        final Instant at = AT.plusSeconds(id);
        return new BatchObservations(id, at, new Observation(id, "bus", at, stop, state, seats));
    }

    private static BatchObservations emptyBatch(final long id) {
        return new BatchObservations(id, AT.plusSeconds(id), null);
    }

    private static InvestigationState stateOf(final TripQualityInvestigation investigation) {
        return new InvestigationState(investigation.routeVersionId(), investigation.vehicleId(), investigation.cursorAt(),
            investigation.cursorBatchId(), investigation.evidenceAt(), investigation.evidenceObservationId(),
            investigation.phase(), investigation.anchorObservationId(), investigation.previousObservationId(),
            investigation.includeCursor(), investigation.canRelease(), investigation.maximumGap(),
            investigation.boundaryCandidateObservationId());
    }

    private record InvestigationState(long routeVersionId, String vehicleId, Instant cursorAt, long cursorBatchId,
        Instant evidenceAt, long evidenceObservationId, Phase phase, long anchorObservationId, Long previousObservationId,
        boolean includeCursor, boolean canRelease, Duration maximumGap, Long boundaryCandidateObservationId) { }
}
