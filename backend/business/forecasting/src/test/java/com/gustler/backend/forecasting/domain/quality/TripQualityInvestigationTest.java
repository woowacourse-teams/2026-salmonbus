package com.gustler.backend.forecasting.domain.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Observation;
import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Previous;
import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Route;
import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Status;
import com.gustler.backend.forecasting.domain.quality.QualityObservationBatch.Row;
import com.gustler.backend.forecasting.domain.quality.TripQualityInvestigation.BatchObservations;
import com.gustler.backend.forecasting.domain.quality.TripQualityInvestigation.Phase;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;
import org.assertj.core.api.AutoCloseableSoftAssertions;
import org.junit.jupiter.api.Test;

class TripQualityInvestigationTest {
    private static final Instant AT = Instant.parse("2026-09-23T00:00:00Z");
    private static final Route ROUTE = new Route(1, 7, 4, null);

    @Test
    void 이상_관측을_처음_발견하면_그_배치부터_시작점을_찾고_미설정_간격은_10분을_사용한다() {
        // given
        Row anomaly = new Row(51, "bus", 71);
        QualityObservationBatch batch = new QualityObservationBatch(5, 1, AT, List.of(anomaly));

        // when
        TripQualityInvestigation investigation = TripQualityInvestigation.start(batch, anomaly, null);

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(investigation.routeVersionId()).isEqualTo(1);
            softly.assertThat(investigation.vehicleId()).isEqualTo("bus");
            softly.assertThat(investigation.phase()).isEqualTo(Phase.SEARCH_START);
            softly.assertThat(investigation.cursorAt()).isEqualTo(AT);
            softly.assertThat(investigation.cursorBatchId()).isEqualTo(5);
            softly.assertThat(investigation.evidenceAt()).isEqualTo(AT);
            softly.assertThat(investigation.evidenceObservationId()).isEqualTo(51);
            softly.assertThat(investigation.anchorObservationId()).isEqualTo(51);
        }
        assertThat(investigation.previousObservationId()).isNull();
        assertThat(investigation.boundaryCandidateObservationId()).isNull();
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(investigation.includeCursor()).isFalse();
            softly.assertThat(investigation.canRelease()).isFalse();
            softly.assertThat(investigation.maximumGap()).isEqualTo(Duration.ofMinutes(10));
        }
    }

    @Test
    void 정상_좌석이거나_차량을_식별할_수_없는_관측은_품질_조사를_시작하지_않는다() {
        // given
        List<Row> observations = List.of(
            new Row(51, "bus", 70),
            new Row(52, "bus", null),
            new Row(53, null, 71),
            new Row(54, " ", 71));
        QualityObservationBatch batch = new QualityObservationBatch(5, 1, AT, observations);


        // when
        for (Row observation : observations) {

            // then

            // when
            Throwable phaseFailure1 = catchThrowable(
                () -> TripQualityInvestigation.start(batch, observation, Duration.ofMinutes(10)));

            // then
            assertThat(phaseFailure1)
                .isNotNull().isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void 차량_관측이_없는_32개_배치를_지나도_출발_후보를_보존하고_탐색_위치를_옮긴다() {
        // given
        BatchObservations anchor = row(400, 4, 1, 44);
        BatchObservations departure = row(410, 4, 2, 44);
        TripQualityInvestigation investigation = new TripQualityInvestigation(
            1, "bus", anchor.at(), anchor.batch(), AT.plusSeconds(420), anchor.observation().id(), Phase.SEARCH_START,
            anchor.observation().id(), null, false, false, Duration.ofMinutes(10), departure.observation().id(), false);
        List<BatchObservations> page = LongStream.range(0, TripQualityInvestigation.BATCH_LIMIT)
            .mapToObj(offset -> emptyBatch(399 - offset))
            .toList();

        // when
        investigation.searchStart(ROUTE, page, Map.of(400L, anchor, 410L, departure));

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(investigation.phase()).isEqualTo(Phase.SEARCH_START);
            softly.assertThat(investigation.cursorBatchId()).isEqualTo(368);
            softly.assertThat(investigation.cursorAt()).isEqualTo(AT.plusSeconds(368));
            softly.assertThat(investigation.anchorObservationId()).isEqualTo(400);
            softly.assertThat(investigation.boundaryCandidateObservationId()).isEqualTo(410L);
            softly.assertThat(investigation.includeCursor()).isFalse();
        }
    }

    @Test
    void 반복된_출발이_다음_페이지에도_있으면_첫_출발까지_찾아_재판정한다() {
        // given
        BatchObservations anomaly = row(400, 5, 0, 71);
        BatchObservations departure = row(399, 4, 2, 44);
        TripQualityInvestigation investigation = investigation(Phase.SEARCH_START, anomaly, false);
        List<BatchObservations> firstPage = new ArrayList<>();
        firstPage.add(departure);
        LongStream.range(0, TripQualityInvestigation.BATCH_LIMIT - 1)
            .mapToObj(offset -> emptyBatch(398 - offset))
            .forEach(firstPage::add);
        investigation.searchStart(ROUTE, firstPage, Map.of(400L, anomaly));
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(investigation.phase()).isEqualTo(Phase.SEARCH_START);
            softly.assertThat(investigation.boundaryCandidateObservationId()).isEqualTo(399L);
        }

        // when
        investigation.searchStart(ROUTE,
            List.of(row(367, 4, 0, 44), row(366, 4, 2, 44), row(365, 3, 2, 44)),
            Map.of(399L, departure));

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(investigation.phase()).isEqualTo(Phase.REPLAY);
            softly.assertThat(investigation.anchorObservationId()).isEqualTo(366);
            softly.assertThat(investigation.cursorBatchId()).isEqualTo(366);
            softly.assertThat(investigation.includeCursor()).isTrue();
        }
        assertThat(investigation.boundaryCandidateObservationId()).isNull();
    }

    @Test
    void 정상_편도에_도달해도_32개_배치를_모두_읽었다면_다음_페이지까지_확인한다() {
        // given
        TripQualityInvestigation investigation = investigation(Phase.REPLAY, row(1, 1, 2, 44), false);
        List<BatchObservations> fullPage = LongStream.rangeClosed(2, 33)
            .mapToObj(id -> row(id, 1, 2, 44))
            .toList();

        // when
        List<OneWayTripAssessment> assessments = investigation.replay(ROUTE, fullPage, null);

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(investigation.canRelease()).isTrue();
            softly.assertThat(investigation.phase()).isEqualTo(Phase.REPLAY);
            softly.assertThat(investigation.cursorBatchId()).isEqualTo(33);
        }

        // when
        investigation.replay(ROUTE, List.of(row(34, 2, 0, 44)), assessments.getLast().asPrevious());

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(investigation.completed()).isTrue();
            softly.assertThat(investigation.previousObservationId()).isEqualTo(34L);
            softly.assertThat(investigation.cursorBatchId()).isEqualTo(34);
            softly.assertThat(investigation.includeCursor()).isFalse();
        }
    }

    @Test
    void 제외된_편도_이후_새_관측이_없으면_조사를_완료하지_않고_기다린다() {
        // given
        BatchObservations anomaly = row(31, 3, 0, 71);
        TripQualityInvestigation investigation = investigation(Phase.REPLAY, anomaly, false);
        Previous previous = new Previous(anomaly.observation(), 1, Status.EXCLUDED);

        // when
        List<OneWayTripAssessment> assessments = investigation.replay(ROUTE, List.of(), previous);

        // then
        assertThat(assessments).isEmpty();
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(investigation.completed()).isFalse();
            softly.assertThat(investigation.phase()).isEqualTo(Phase.REPLAY);
            softly.assertThat(investigation.canRelease()).isFalse();
            softly.assertThat(investigation.cursorBatchId()).isEqualTo(31);
            softly.assertThat(investigation.previousObservationId()).isEqualTo(31L);
        }
    }

    @Test
    void 시작점_탐색은_시작점_탐색_단계에서만_진행한다() {
        // when
        BatchObservations anchor = row(1, 1, 2, 44);
        TripQualityInvestigation replaying = investigation(Phase.REPLAY, anchor, false);
        TripQualityInvestigation completed = investigation(Phase.DONE, anchor, true);


        // then

        // when
        Throwable phaseFailure1 = catchThrowable(() -> replaying.searchStart(ROUTE, List.of(), Map.of()));

        // then
        assertThat(phaseFailure1)
            .isNotNull().isInstanceOf(IllegalStateException.class);

        // when
        Throwable phaseFailure2 = catchThrowable(() -> completed.searchStart(ROUTE, List.of(), Map.of()));

        // then
        assertThat(phaseFailure2)
            .isNotNull().isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 과거_조사의_단계명이_DONE이어도_완료_전이면_재판정한다() {
        // given
        BatchObservations anchor = row(1, 1, 2, 44);
        TripQualityInvestigation pending = new TripQualityInvestigation(1, "bus", anchor.at(), anchor.batch(),
            anchor.at(), anchor.observation().id(), Phase.DONE, anchor.observation().id(), null, true, false,
            Duration.ofMinutes(10), null, false);

        // when
        List<OneWayTripAssessment> actual = pending.replay(ROUTE, List.of(row(2, 2, 2, 44)), null);

        // then
        assertThat(actual).hasSize(1);
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(pending.completed()).isFalse();
            softly.assertThat(pending.phase()).isEqualTo(Phase.REPLAY);
        }
    }

    private static TripQualityInvestigation investigation(Phase phase, BatchObservations anchor,
        final boolean canRelease) {
        return new TripQualityInvestigation(1, "bus", anchor.at(), anchor.batch(), anchor.at(), anchor.observation()
                .id(), phase,
            anchor.observation().id(), null, true, canRelease, Duration.ofMinutes(10), null, phase == Phase.DONE);
    }

    private static BatchObservations row(
        final long id,
        final int stop,
        final int state,
        final int seats
    ) {
        Instant at = AT.plusSeconds(id);
        return new BatchObservations(id, at, new Observation(id, "bus", at, stop, state, seats));
    }

    private static BatchObservations emptyBatch(final long id) {
        return new BatchObservations(id, AT.plusSeconds(id), null);
    }
}
