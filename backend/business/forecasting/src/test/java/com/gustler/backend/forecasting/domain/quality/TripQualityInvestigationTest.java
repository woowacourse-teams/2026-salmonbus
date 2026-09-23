package com.gustler.backend.forecasting.domain.quality;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class TripQualityInvestigationTest {
    private static final Instant AT = Instant.parse("2026-09-23T00:00:00Z");
    private static final Route ROUTE = new Route(1, 7, 4, null);

    @Test
    void 차량_관측이_없는_32개_배치를_지나도_출발_후보를_보존하고_탐색_위치를_옮긴다() {
        // given
        final BatchObservations anchor = row(400, 4, 1, 44);
        final BatchObservations departure = row(410, 4, 2, 44);
        final TripQualityInvestigation investigation = new TripQualityInvestigation(
            1, "bus", anchor.at(), anchor.batch(), AT.plusSeconds(420), Phase.SEARCH_START,
            anchor.observation().id(), null, false, false, Duration.ofMinutes(10), departure.observation().id());
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
        return new TripQualityInvestigation(1, "bus", anchor.at(), anchor.batch(), anchor.at(), phase,
            anchor.observation().id(), null, true, canRelease, Duration.ofMinutes(10), null);
    }

    private static BatchObservations row(final long id, final int stop, final int state, final int seats) {
        final Instant at = AT.plusSeconds(id);
        return new BatchObservations(id, at, new Observation(id, "bus", at, stop, state, seats));
    }

    private static BatchObservations emptyBatch(final long id) {
        return new BatchObservations(id, AT.plusSeconds(id), null);
    }
}
