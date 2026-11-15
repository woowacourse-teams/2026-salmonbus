package com.gustler.backend.api.board.infrastructure.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.board.application.BoardSnapshot;
import com.gustler.backend.api.board.application.BoardVehicleObservation;
import com.gustler.backend.api.board.application.SnapshotObservation;
import com.gustler.backend.api.board.application.StoredPrediction;
import com.gustler.backend.api.board.domain.BoardStop;
import com.gustler.backend.api.board.support.BoardDatabaseFixture;
import com.gustler.backend.api.board.support.BoardDatabaseFixture.RouteContext;
import com.gustler.backend.api.observation.ObservationFreshness;
import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.support.IntegrationTest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.AutoCloseableSoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class JpaBoardQueryRepositoryIntegrationTest {
    @Autowired
    private JpaBoardQueryRepository repository;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void 예보가_없어도_선택한_관측_묶음과_노선_판본의_차량을_조회한다() {
        // given
        BoardDatabaseFixture fixture = new BoardDatabaseFixture(jdbcClient);
        OffsetDateTime now = OffsetDateTime.now();
        RouteContext route = route(fixture, "204000057", now);
        RouteContext otherRoute = route(fixture, "234000050", now);
        final long batch = fixture.insertBatch(route, now, "SUCCESS_ROWS", 2);
        final long otherBatch = fixture.insertBatch(route, now.minusMinutes(1), "SUCCESS_ROWS", 1);
        final long otherRouteBatch = fixture.insertBatch(otherRoute, now, "SUCCESS_ROWS", 1);
        final long observation = fixture.insertObservation(route, batch, 1, null, 1, "STOP-1", now);
        fixture.insertObservation(route, batch, 2, null, 1, "STOP-1", now);
        fixture.insertObservation(route, otherBatch, 1, "OLD", 1, "STOP-1", now);
        fixture.insertObservation(otherRoute, otherRouteBatch, 1, "OTHER", 1, "STOP-1", now);
        // 정류장에 도착한 차량의 통과 순번은 현재 정류장 순번보다 하나 작다.

        // when
        jdbcClient.sql("UPDATE vehicle_observation SET passed_stop_order = 0, running_state = 1 WHERE id = :id")
            .param("id", observation).update();

        // then
        assertThat(repository.findObservedVehicles(batch, route.routeVersionId()))
            .containsExactlyInAnyOrder(new BoardVehicleObservation(null, 1, 0, 1),
                new BoardVehicleObservation(null, 2, 1, 1));
        assertThat(repository.findObservedVehicles(batch, otherRoute.routeVersionId())).isEmpty();
        assertThat(repository.findRecordedPredictions(batch, sourceDateOf(batch))).isEmpty();
    }

    @Test
    void 여러_판본과_묶음을_한_번에_읽어도_짝마다_읽은_정류장과_차량과_같다() {
        // given
        BoardDatabaseFixture fixture = new BoardDatabaseFixture(jdbcClient);
        OffsetDateTime now = OffsetDateTime.now();
        RouteContext route = route(fixture, "204000057", now);
        RouteContext otherRoute = route(fixture, "234000050", now);
        fixture.insertStop(route, 2, "STOP-2", "다음 정류장", "UP", true);
        final long batch = fixture.insertBatch(route, now, "SUCCESS_ROWS", 2);
        final long otherRouteBatch = fixture.insertBatch(otherRoute, now, "SUCCESS_ROWS", 1);
        final long emptyBatch = fixture.insertBatch(route, now.minusMinutes(1), "SUCCESS_EMPTY", 0);
        fixture.insertObservation(route, batch, 1, "MINE", 1, "STOP-1", now);
        fixture.insertObservation(otherRoute, otherRouteBatch, 1, "OTHER", 1, "STOP-1", now);
        Map<Long, Long> pairs = new LinkedHashMap<>();
        pairs.put(batch, route.routeVersionId());
        pairs.put(otherRouteBatch, otherRoute.routeVersionId());
        pairs.put(emptyBatch, route.routeVersionId());

        // when
        Map<Long, List<BoardStop>> stops = repository.findStops(
            List.of(route.routeVersionId(), otherRoute.routeVersionId()));
        Map<Long, List<BoardVehicleObservation>> vehicles = repository.findObservedVehicles(pairs);

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(stops.get(route.routeVersionId()))
                .containsExactlyElementsOf(repository.findStops(route.routeVersionId()));
            softly.assertThat(stops.get(otherRoute.routeVersionId()))
                .containsExactlyElementsOf(repository.findStops(otherRoute.routeVersionId()));
            softly.assertThat(vehicles.get(batch)).extracting(BoardVehicleObservation::vehicleId)
                .containsExactly("MINE");
            softly.assertThat(vehicles.get(batch))
                .containsExactlyInAnyOrderElementsOf(repository.findObservedVehicles(batch, route.routeVersionId()));
            softly.assertThat(vehicles.get(otherRouteBatch)).extracting(BoardVehicleObservation::vehicleId)
                .containsExactly("OTHER");
            softly.assertThat(vehicles).doesNotContainKey(emptyBatch);
        }
    }

    @Test
    void 여러_묶음의_예보를_한_번에_읽어도_묶음마다_읽은_예보와_같고_예측이_없는_묶음은_빠진다() {
        // given
        BoardDatabaseFixture fixture = new BoardDatabaseFixture(jdbcClient);
        OffsetDateTime now = OffsetDateTime.now().withNano(0);
        RouteContext route = route(fixture, "204000057", now);
        fixture.insertStop(route, 2, "STOP-2", "다음 정류장", "UP", true);
        final long model = fixture.insertModel("model-active", "ACTIVE", now.minusDays(1));
        final long batch = fixture.insertBatch(route, now, "SUCCESS_ROWS", 2);
        final long otherBatch = fixture.insertBatch(route, now.minusMinutes(1), "SUCCESS_ROWS", 2);
        final long unpublishedBatch = fixture.insertBatch(route, now.minusMinutes(2), "SUCCESS_ROWS", 1);
        fixture.insertPublication(batch, model, now);
        fixture.insertPublication(otherBatch, model, now);
        final long matching = fixture.insertObservation(route, batch, 1, "MATCH", 1, "STOP-1", now);
        fixture.insertObservation(route, batch, 2, "NO-FORECAST", 1, "STOP-1", now);
        final long otherMatching = fixture.insertObservation(route, otherBatch, 1, "OTHER-MATCH", 1, "STOP-1", now);
        fixture.insertObservation(route, otherBatch, 2, "OTHER-NO-FORECAST", 1, "STOP-1", now);
        fixture.insertForecast(matching, 2, model, 0.2, 20.0);
        fixture.insertForecast(otherMatching, 2, model, 0.5, 8.0);
        Map<Long, LocalDate> sourceDates = new LinkedHashMap<>();
        sourceDates.put(batch, sourceDateOf(batch));
        sourceDates.put(otherBatch, sourceDateOf(otherBatch));
        sourceDates.put(unpublishedBatch, sourceDateOf(unpublishedBatch));

        // when
        Map<Long, List<StoredPrediction>> predictions = repository.findRecordedPredictions(sourceDates);

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(predictions.get(batch)).extracting(StoredPrediction::vehicleId)
                .containsExactly("MATCH");
            softly.assertThat(predictions.get(otherBatch)).extracting(StoredPrediction::vehicleId)
                .containsExactly("OTHER-MATCH");
            softly.assertThat(predictions.get(batch))
                .containsExactlyElementsOf(repository.findRecordedPredictions(batch, sourceDateOf(batch)));
            softly.assertThat(predictions.get(otherBatch))
                .containsExactlyElementsOf(repository.findRecordedPredictions(otherBatch, sourceDateOf(otherBatch)));
            softly.assertThat(predictions).doesNotContainKey(unpublishedBatch);
        }
    }

    @Test
    void 미발행_배치는_제외하고_관측_시각이_가장_최근인_빈_발행을_선택한다() {
        // given
        BoardDatabaseFixture fixture = new BoardDatabaseFixture(jdbcClient);
        OffsetDateTime now = OffsetDateTime.now().withNano(0);
        RouteContext route = route(fixture, "204000057", now);
        fixture.insertStop(route, 2, "STOP-2", "다음 정류장", "UP", true);
        final long model = fixture.insertModel("model-active", "ACTIVE", now.minusDays(1));
        final long earlierBatch = fixture.insertBatch(route, now.minusMinutes(2), "SUCCESS_ROWS", 1);
        final long observation = fixture.insertObservation(route, earlierBatch, 1, "OLD", 1, "STOP-1", now);
        // 발행 시각이 늦어도 이전 관측이 최신 빈 발행을 대신하지 않는다.
        fixture.insertPublication(earlierBatch, model, now.minusSeconds(5));
        fixture.insertForecast(observation, 2, model, 0.2, 20.0);
        final long emptyBatch = fixture.insertBatch(route, now.minusMinutes(1), "SUCCESS_EMPTY", 0);
        fixture.insertPublication(emptyBatch, model, now.minusSeconds(50));
        fixture.insertBatch(route, now, "SUCCESS_ROWS", 1);

        // when
        SnapshotObservation selected = repository.findSnapshot(new RouteId(route.sourceRouteId()))
            .orElseThrow().observation().orElseThrow();

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(selected.batchId()).isEqualTo(emptyBatch);
            softly.assertThat(selected.observedAt().toInstant()).isEqualTo(now.minusMinutes(1).toInstant());
            softly.assertThat(selected.vehiclesInService()).isZero();
        }
        assertThat(repository.findRecordedPredictions(earlierBatch, sourceDateOf(earlierBatch))).hasSize(1);
        assertThat(repository.findRecordedPredictions(selected.batchId(), selected.forecastDate())).isEmpty();
    }

    @Test
    void 예측을_실은_실행_줄의_묶음에서만_예보를_조회한다() {
        // given
        BoardDatabaseFixture fixture = new BoardDatabaseFixture(jdbcClient);
        OffsetDateTime now = OffsetDateTime.now().withNano(0);
        RouteContext route = route(fixture, "204000057", now);
        fixture.insertStop(route, 2, "STOP-2", "다음 정류장", "UP", true);
        final long model = fixture.insertModel("model-active", "ACTIVE", now.minusDays(1));
        final long batch = fixture.insertBatch(route, now, "SUCCESS_ROWS", 1);
        final long otherBatch = fixture.insertBatch(route, now.minusMinutes(1), "SUCCESS_ROWS", 1);
        fixture.insertPublication(batch, model, now);
        fixture.insertPublication(otherBatch, model, now);
        final long matchingObservation = fixture.insertObservation(route, batch, 1, "MATCH", 1, "STOP-1", now);
        final long lateObservation = fixture.insertObservation(route, otherBatch, 1, "LATE", 1, "STOP-1", now);
        fixture.insertForecast(matchingObservation, 2, model, 0.2, 20.0);
        fixture.insertForecast(lateObservation, 2, model, 0.8, 5.0);

        // when
        jdbcClient.sql("UPDATE forecast_run SET outcome = 'LATE' WHERE observation_batch_id = ?")
            .param(otherBatch).update();

        // then
        assertThat(repository.findRecordedPredictions(batch, sourceDateOf(batch)))
            .extracting(StoredPrediction::vehicleId)
            .containsExactly("MATCH");
        assertThat(repository.findRecordedPredictions(otherBatch, sourceDateOf(otherBatch))).isEmpty();
    }

    @Test
    void 발행된_예보가_없는_판본은_최신_정상_묶음을_발행되지_않은_관측으로_고른다() {
        // given
        BoardDatabaseFixture fixture = new BoardDatabaseFixture(jdbcClient);
        OffsetDateTime now = OffsetDateTime.now().withNano(0);
        RouteContext route = route(fixture, "204000057", now);
        RouteContext otherRoute = route(fixture, "234000050", now);
        final long model = fixture.insertModel("model-active", "ACTIVE", now.minusDays(1));
        final long otherRouteBatch = fixture.insertBatch(otherRoute, now.minusSeconds(10), "SUCCESS_EMPTY", 0);
        fixture.insertPublication(otherRouteBatch, model, now.minusSeconds(10));
        fixture.insertBatch(route, now.minusMinutes(2), "SUCCESS_ROWS", 1);
        final long latestNormalBatch = fixture.insertBatch(route, now.minusMinutes(1), "SUCCESS_EMPTY", 0);
        fixture.insertBatch(route, now.minusSeconds(30), "FAILED_UNREADABLE", null);

        // when
        SnapshotObservation selected = repository.findSnapshot(new RouteId(route.sourceRouteId()))
            .orElseThrow().observation().orElseThrow();

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(selected.batchId()).isEqualTo(latestNormalBatch);
            softly.assertThat(selected.forecastPublished()).isFalse();
        }
    }

    @Test
    void 발행된_예보가_있는_판본은_최신_발행_묶음을_발행된_관측으로_고른다() {
        // given
        BoardDatabaseFixture fixture = new BoardDatabaseFixture(jdbcClient);
        OffsetDateTime now = OffsetDateTime.now().withNano(0);
        RouteContext route = route(fixture, "204000057", now);
        final long model = fixture.insertModel("model-active", "ACTIVE", now.minusDays(1));
        final long publishedBatch = fixture.insertBatch(route, now.minusMinutes(2), "SUCCESS_EMPTY", 0);
        fixture.insertPublication(publishedBatch, model, now.minusMinutes(2));
        fixture.insertBatch(route, now.minusMinutes(1), "SUCCESS_EMPTY", 0);

        // when
        SnapshotObservation selected = repository.findSnapshot(new RouteId(route.sourceRouteId()))
            .orElseThrow().observation().orElseThrow();

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(selected.batchId()).isEqualTo(publishedBatch);
            softly.assertThat(selected.forecastPublished()).isTrue();
        }
    }

    @Test
    void 발행된_묶음이_신선도_최대_창보다_오래되면_더_최신의_미발행_묶음이_있어도_관측을_고르지_않는다() {
        // given
        BoardDatabaseFixture fixture = new BoardDatabaseFixture(jdbcClient);
        OffsetDateTime now = OffsetDateTime.now().withNano(0);
        RouteContext route = route(fixture, "204000057", now);
        final long model = fixture.insertModel("model-active", "ACTIVE", now.minusDays(1));
        OffsetDateTime outsideWindow = now.minus(ObservationFreshness.MAX_WINDOW).minusSeconds(1);
        final long oldPublishedBatch = fixture.insertBatch(route, outsideWindow, "SUCCESS_EMPTY", 0);
        fixture.insertPublication(oldPublishedBatch, model, outsideWindow);
        fixture.insertBatch(route, now.minusSeconds(30), "SUCCESS_EMPTY", 0);

        // when
        BoardSnapshot snapshot = repository.findSnapshot(new RouteId(route.sourceRouteId())).orElseThrow();

        // then
        assertThat(snapshot.observation()).isEmpty();
    }

    @Test
    void 발행된_예보가_없는_판본은_정상_묶음이_신선도_최대_창보다_오래되면_관측을_고르지_않는다() {
        // given
        BoardDatabaseFixture fixture = new BoardDatabaseFixture(jdbcClient);
        OffsetDateTime now = OffsetDateTime.now().withNano(0);
        RouteContext route = route(fixture, "204000057", now);
        fixture.insertBatch(route, now.minus(ObservationFreshness.MAX_WINDOW).minusSeconds(1), "SUCCESS_EMPTY", 0);

        // when
        BoardSnapshot snapshot = repository.findSnapshot(new RouteId(route.sourceRouteId())).orElseThrow();

        // then
        assertThat(snapshot.observation()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "confirmed_at = confirmed_at + interval '1 second'",
        "prediction_count = prediction_count + 1",
        "model_deployment_id = model_deployment_id + 1"
    })
    void 같은_회차의_발행_정보가_바뀌면_캐시_판본도_바뀐다(String assignment) {
        // given
        BoardDatabaseFixture fixture = new BoardDatabaseFixture(jdbcClient);
        OffsetDateTime now = OffsetDateTime.now().withNano(0);
        RouteContext route = route(fixture, "204000057", now);
        final long model = fixture.insertModel("model-active", "ACTIVE", now.minusDays(1));
        final long batch = fixture.insertBatch(route, now, "SUCCESS_EMPTY", 0);
        fixture.insertPublication(batch, model, now);
        RouteId routeId = new RouteId(route.sourceRouteId());
        BoardSnapshot before = repository.findSnapshot(routeId).orElseThrow();

        // when
        jdbcClient.sql("UPDATE forecast_run SET " + assignment + " WHERE observation_batch_id = ?")
            .param(batch).update();
        BoardSnapshot after = repository.findSnapshot(routeId).orElseThrow();

        // then
        assertThat(after.observation()).isEqualTo(before.observation());
        assertThat(after.revision()).isNotEqualTo(before.revision());
    }

    @Test
    void 발행이_없는_노선도_발행_판본을_읽는다() {
        // given
        BoardDatabaseFixture fixture = new BoardDatabaseFixture(jdbcClient);
        OffsetDateTime now = OffsetDateTime.now().withNano(0);
        RouteContext route = route(fixture, "204000057", now);
        fixture.insertBatch(route, now, "SUCCESS_EMPTY", 0);

        // when
        BoardSnapshot snapshot = repository.findSnapshot(new RouteId(route.sourceRouteId())).orElseThrow();

        // then
        assertThat(snapshot.revision()).isEqualTo("c-|n-|m-");
    }

    private LocalDate sourceDateOf(final long batchId) {
        return jdbcClient.sql("""
                SELECT (scheduled_at AT TIME ZONE 'Asia/Seoul')::date FROM observation_batch WHERE id = ?
                """)
            .param(batchId)
            .query(LocalDate.class)
            .single();
    }

    private RouteContext route(BoardDatabaseFixture fixture, String id, OffsetDateTime now) {
        RouteContext route = fixture.insertRoute(id, "노선", "기점", "종점", null,
            "05:00", "23:00", null, null, now.minusDays(1));
        fixture.insertStop(route, 1, "STOP-1", "기점", "UP", true);
        return route;
    }
}
