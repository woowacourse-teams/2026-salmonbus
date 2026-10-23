package com.gustler.backend.forecasting.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.gustler.backend.forecasting.application.evaluation.ObservationDeliveryService;
import com.gustler.backend.forecasting.application.evaluation.ObservationDeliveryService.Round;
import com.gustler.backend.forecasting.application.evaluation.RecentObservationBatches;
import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalCandidate;
import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import com.gustler.backend.observations.api.ObservationDeliveryBatch;
import com.gustler.backend.observations.api.ObservationDeliveryBatch.Kind;
import com.gustler.backend.observations.api.ObservationDeliveryBatch.Row;
import com.gustler.backend.support.ConfirmedTripFixture;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@ForecastingIntegrationTest
@Transactional
class ObservationDeliveryRecoveryTest {
    private static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");
    private final List<Row> rows = new ArrayList<>();
    @Autowired
    JdbcClient jdbc;
    @Autowired
    JdbcArrivalObservationQuery query;
    @Autowired
    JdbcRecentObservationBatches batches;
    @Autowired
    RouteDataQualityAccess quality;
    private long version;
    private long batch;

    @BeforeEach
    void 수집할_노선과_배치를_만든다() {
        final long route = jdbc.sql("""
            INSERT INTO route (public_route_id, source_id, source_route_id, display_name, start_stop_name, end_stop_name)
            VALUES ('memory-test', 'GBIS', 'memory-test', '테스트', '출발', '도착') RETURNING id
            """)
            .query(Long.class)
            .single();
        version = jdbc.sql("""
            INSERT INTO route_version (route_id, content_digest, valid_from, turn_sequence)
            VALUES (:route, :digest, :at, 5) RETURNING id
            """).param("route", route).param("digest", "0".repeat(64)).param("at", NOW.atOffset(ZoneOffset.UTC))
            .query(Long.class).single();
        for (int stop = 1; stop <= 10; stop++) {
            jdbc.sql("""
                INSERT INTO route_stop (route_version_id, stop_order, stop_id, name, direction, boarding_allowed)
                VALUES (:version, :stop, :id, '정류장', 'UP', true)
                """).param("version", version).param("stop", stop).param("id", "stop-" + stop).update();
        }
        batch = jdbc.sql("""
            INSERT INTO observation_batch (route_version_id, scheduled_at, attempt_number, attempt_key,
                requested_at, response_received_at, outcome, normalization_version, collection_strategy_version, stored_rows)
            VALUES (:version, :at, 1, 'memory-test', :at, :at, 'SUCCESS_ROWS', 'test-v1', 'test-v1', 0) RETURNING id
            """)
            .param("version", version)
            .param("at", NOW.atOffset(ZoneOffset.UTC))
            .query(Long.class)
            .single();
        quality.lock(version);
    }

    @Test
    void 수집_원본으로_만든_방향과_잔여석_판정이_SQL과_같다() {
        // given
        add(5, 1, 10);
        add(5, 2, 10);
        add(1, 1, 10);
        add(1, 2, 10);
        add(6, 2, 71);
        add(7, 2, null);
        JdbcRecentObservationBatches reads = spy(new JdbcRecentObservationBatches(jdbc));
        ObservationDeliveryService memory = memory(reads);
        memory.receive(1, payload());

        // when
        Round round = memory.begin(NOW);
        for (Row row : rows) {
            assertThat(round.read(query, version, 1, false, row.vehicleId(), NOW.minusSeconds(60), 100))
                .isEqualTo(query.findAfter(version, row.vehicleId(), NOW.minusSeconds(60), 100));
        }

        // then
        verify(reads, never()).restore(anyLong(), anyList(), anyInt());
        assertThat(jdbc.sql("SELECT to_regclass('observation_delivery') IS NULL")
            .query(Boolean.class)
            .single()).isTrue();
    }

    @Test
    void 캐시에서_찾지_못한_관측을_DB에서_읽어도_캐시를_사용하지_않았을_때와_정산_결과가_같다() {
        // given
        add(2, 2, 10);
        JdbcRecentObservationBatches reads = spy(new JdbcRecentObservationBatches(jdbc));
        ObservationDeliveryService memory = memory(reads);

        // when
        assertThat(read(memory, 1)).isEqualTo(expected());
        assertThat(read(memory, 1)).isEqualTo(expected());

        // then
        verify(reads, times(1)).restore(anyLong(), anyList(), anyInt());
    }

    @Test
    void 재시작으로_메모리가_비면_전달_기록_없이_DB의_관측을_읽는다() {
        // given
        add(2, 2, 10);

        // when
        List<ArrivalCandidate> actual = read(memory(batches), 1);

        // then
        assertThat(actual).isEqualTo(expected()).hasSize(1);
    }

    @Test
    void 품질검사로_제외된_편도는_늦은_원본_전달로_다시_정산하지_않는다() {
        // given
        add(2, 2, 10);
        ObservationDeliveryService memory = memory(batches);
        memory.receive(1, payload());
        assertThat(read(memory, 1).getFirst().qualityDirection()).isNotNull();
        final long observation = rows.getFirst().observationId();
        ConfirmedTripFixture.include(jdbc, observation);
        jdbc.sql("UPDATE vehicle_one_way_trip SET status = 'EXCLUDED'").update();
        jdbc.sql("UPDATE route_data_quality SET quality_revision = quality_revision + 1").update();

        // when
        memory.receive(1, payload());
        List<ArrivalCandidate> actual = read(memory, 2);

        // then
        assertThat(actual).isEqualTo(expected());
        assertThat(actual.getFirst().qualityDirection()).isNull();
    }

    @Test
    void 기존_배치를_비우면_메모리의_관측도_조회하지_않는다() {
        // given
        add(2, 2, 10);
        ObservationDeliveryService memory = memory(batches);
        memory.receive(1, payload());
        read(memory, 1);

        // when
        jdbc.sql("DELETE FROM vehicle_observation WHERE observation_batch_id = ?").param(batch).update();
        jdbc.sql(
        "UPDATE observation_batch SET response_received_at = NULL, stored_rows = NULL, attempt_number = 2 WHERE id = ?")
            .param(batch)
            .update();

        // then
        assertThat(read(memory, 1)).isEqualTo(expected()).isEmpty();
    }

    private void add(final int stop, final int state, Integer seats) {
        final int passed = passedStopOf(state, stop);
        String vehicle = vehicleForNextRow();
        final long id = jdbc.sql("""
            INSERT INTO vehicle_observation (observation_batch_id, route_version_id, source_row_number,
                vehicle_id, stop_order, stop_id, passed_stop_order, running_state, remaining_seats, seat_unknown_reason)
            VALUES (:batch, :version, :row, :vehicle, :stop, :stopId, :passed, :state, :seats, :reason) RETURNING id
            """).param("batch", batch).param("version", version).param("row", rows.size())
            .param("stop", stop).param("stopId", "stop-" + stop).param("passed", passed)
            .param("vehicle", vehicle).param("state", state).param("seats", seats).param("reason", seatReason(seats))
            .query(Long.class).single();
        rows.add(new Row(id, vehicle, seats, passed, null, stop, state));
        jdbc.sql("UPDATE observation_batch SET stored_rows = :rows WHERE id = :id")
            .param("rows", rows.size()).param("id", batch).update();
    }

    private ObservationDeliveryBatch payload() {
        return new ObservationDeliveryBatch(batch, version, 1, Kind.STORED, NOW, rows);
    }

    private ObservationDeliveryService memory(RecentObservationBatches source) {
        return new ObservationDeliveryService(source, Clock.fixed(NOW, ZoneOffset.UTC), 100, Duration.ofMinutes(30));
    }

    private List<ArrivalCandidate> read(ObservationDeliveryService memory, final long revision) {
        return memory.begin(NOW).read(query, version, revision, false, "bus", NOW.minusSeconds(60), 100);
    }

    private List<ArrivalCandidate> expected() {
        return query.findAfter(version, "bus", NOW.minusSeconds(60), 100);
    }

    private static int passedStopOf(final int state, final int stop) {
        if (state == 2) {
            return stop;
        }
        return stop - 1;
    }

    private String vehicleForNextRow() {
        if (rows.isEmpty()) {
            return "bus";
        }
        return "bus-" + rows.size();
    }

    private static String seatReason(Integer seats) {
        if (seats == null) {
            return "NOT_REPORTED";
        }
        return null;
    }
}
