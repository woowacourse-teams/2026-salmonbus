package com.gustler.backend.forecasting.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsVersion;
import com.gustler.backend.forecasting.domain.statistics.StopDemandCell;
import com.gustler.backend.forecasting.domain.statistics.StopDemandMeasurement;
import com.gustler.backend.forecasting.domain.statistics.TimeSlot;
import com.gustler.backend.support.IntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class JdbcDemandStatisticsVersionTransactionTest {

    private static final String CALCULATION_VERSION = "observed-max-capacity-v1";
    private static final Instant DATA_UNTIL = Instant.parse("2026-08-19T04:00:00Z");

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JdbcStopDemandStatisticsRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private long routeId;
    private long routeVersionId;

    @BeforeEach
    void 노선과_집계할_정류장을_준비한다() {
        String routeKey = "TX" + UUID.randomUUID().toString().substring(0, 16);
        routeId = jdbc.sql("""
                INSERT INTO route (public_route_id, source_id, source_route_id,
                    display_name, start_stop_name, end_stop_name)
                VALUES (?, 'GBIS', ?, '통계 검증', '출발', '도착') RETURNING id
                """)
            .params(routeKey, routeKey).query(Long.class).single();
        routeVersionId = jdbc.sql("""
                INSERT INTO route_version (route_id, content_digest, valid_from)
                VALUES (?, ?, CURRENT_TIMESTAMP) RETURNING id
                """)
            .params(routeId, "0".repeat(64)).query(Long.class).single();
        jdbc.sql("""
                INSERT INTO route_stop (route_version_id, stop_order, stop_id, name, direction, boarding_allowed)
                VALUES (?, 1, 'stop-1', '출발', 'UP', true)
                """).param(routeVersionId).update();
    }

    @AfterEach
    void 저장한_테스트_자료를_정리한다() {
        jdbc.sql("DELETE FROM stop_demand_statistics WHERE route_version_id = ?").param(routeVersionId).update();
        jdbc.sql("DELETE FROM demand_statistics_version WHERE route_version_id = ?").param(routeVersionId).update();
        jdbc.sql("DELETE FROM route_data_quality WHERE route_id = ?").param(routeId).update();
        jdbc.sql("DELETE FROM route_stop WHERE route_version_id = ?").param(routeVersionId).update();
        jdbc.sql("DELETE FROM route_version WHERE id = ?").param(routeVersionId).update();
        jdbc.sql("DELETE FROM route WHERE id = ?").param(routeId).update();
    }

    @Test
    void 셀_저장에_실패하면_앞서_저장한_버전과_셀도_함께_취소한다() {
        // given 두 번째 정류장은 노선에 존재하지 않는다.
        DemandStatisticsVersion version = version(1, List.of(measurement(1), measurement(2)));

        // when
        assertThatThrownBy(() -> repository.append(version)).isInstanceOf(DataIntegrityViolationException.class);

        // then
        assertThat(count("demand_statistics_version")).isZero();
        assertThat(count("stop_demand_statistics")).isZero();
        assertThat(repository.currentRevision(routeVersionId, CALCULATION_VERSION)).isZero();
    }

    @Test
    void 같은_노선의_동시_집계는_서로_다른_버전을_순서대로_저장한다() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> appendNextVersion(start));
            var second = executor.submit(() -> appendNextVersion(start));
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }

        assertThat(repository.currentRevision(routeVersionId, CALCULATION_VERSION)).isEqualTo(2);
        assertThat(count("demand_statistics_version")).isEqualTo(2);
        assertThat(count("stop_demand_statistics")).isEqualTo(2);
        assertThat(jdbc.sql("""
                SELECT version.cell_count = 1 AND version.quality_revision = cell.quality_revision
                    AND version.input_checkpoint IS NULL
                FROM demand_statistics_version version
                JOIN stop_demand_statistics cell USING (route_version_id, calculation_version, revision)
                WHERE version.route_version_id = ? ORDER BY version.revision
                """).param(routeVersionId).query(Boolean.class).list()).containsExactly(true, true);
    }

    private void appendNextVersion(CountDownLatch start) {
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시 집계를 시작하지 못했다");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            repository.readHourlyTotals(routeVersionId, DATA_UNTIL);
            final int revision = repository.currentRevision(routeVersionId, CALCULATION_VERSION) + 1;
            repository.append(version(revision, List.of(measurement(1))));
        });
    }

    private DemandStatisticsVersion version(final int revision, List<StopDemandMeasurement> measurements) {
        return new DemandStatisticsVersion(routeVersionId, CALCULATION_VERSION, revision, DATA_UNTIL,
            DATA_UNTIL.plusSeconds(1), measurements);
    }

    private StopDemandMeasurement measurement(final int stopOrder) {
        return new StopDemandMeasurement(TimeSlot.MORNING, new StopDemandCell(stopOrder, 0.5, 0.2, 2, 1));
    }

    private long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE route_version_id = ?")
            .param(routeVersionId).query(Long.class).single();
    }
}
