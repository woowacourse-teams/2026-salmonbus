package com.gustler.backend.migration.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.migration.quality.TripQualityMaintenance;
import com.gustler.backend.processor.TripQualityRepository;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

class TripQualityMaintenanceTest extends PostgresMigrationTestSupport {
    @BeforeEach
    void 이전_테스트가_커밋한_노선과_관측을_비운다() throws Exception {
        try (var connection = connection(); var statement = connection.createStatement()) {
            statement.execute("TRUNCATE route CASCADE");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 편도_재판정은_롤백한_관측_묶음부터_재개하고_완료_후_재실행해도_원본_3건을_유지한다(boolean configured) throws Exception {
        // given: 같은 차량의 출발 44석, 다음 정류소 82석, 회차지 출발 44석을 준비한다.
        Instant at = Instant.parse("2026-09-21T00:00:00Z");
        try (var connection = connection()) {
            var jdbc = JdbcClient.create(new SingleConnectionDataSource(connection, true));
            long route = jdbc.sql("""
                INSERT INTO route(public_route_id, source_id, source_route_id, display_name, start_stop_name, end_stop_name)
                VALUES ('900000010', 'TEST', '900000010', 'test', 'a', 'b') RETURNING id
                """).query(Long.class).single();
            long version = jdbc.sql("INSERT INTO route_version(route_id, content_digest, valid_from, turn_sequence) VALUES (?, ?, ?, 3) RETURNING id")
                .param(route).param("0".repeat(64)).param(at.atOffset(ZoneOffset.UTC)).query(Long.class).single();
            for (int stop = 1; stop <= 5; stop++) {
                jdbc.sql("INSERT INTO route_stop VALUES (?, ?, ?, ?, ?, true)")
                    .param(version).param(stop).param("s" + stop).param("stop" + stop).param(stop <= 3 ? "UP" : "DOWN").update();
            }
            if (configured) {
                jdbc.sql("UPDATE route_version SET maximum_observation_gap_seconds = 60, observation_gap_evidence = 'synthetic test' WHERE id = ?").param(version).update();
            }
            for (int i = 1; i <= 3; i++) {
                OffsetDateTime time = at.plusSeconds(i * 10).atOffset(ZoneOffset.UTC);
                long batch = jdbc.sql("""
                    INSERT INTO observation_batch(route_version_id, scheduled_at, attempt_number, attempt_key,
                        requested_at, response_received_at, outcome, normalization_version, collection_strategy_version)
                    VALUES (?, ?, 1, ?, ?, ?, 'SUCCESS_ROWS', 'test', 'test') RETURNING id
                    """).param(version).param(time).param("batch" + i).param(time).param(time).query(Long.class).single();
                jdbc.sql("""
                    INSERT INTO vehicle_observation(observation_batch_id, route_version_id, source_row_number,
                        vehicle_id, stop_order, stop_id, running_state, passed_stop_order, remaining_seats)
                    VALUES (?, ?, 0, 'bus', ?, ?, 2, ?, ?)
                    """).param(batch).param(version).param(i).param("s" + i).param(i).param(i == 2 ? 82 : 44).update();
            }
            var maintenance = new TripQualityMaintenance(jdbc);
            Instant until = at.plusSeconds(60);
            connection.setAutoCommit(false);
            connection.setReadOnly(true);

            // when
            var preview = maintenance.preview(version, until);

            // then
            assertThat(((Number) preview.get("above_range")).intValue()).isEqualTo(1);
            assertThat(jdbc.sql("SELECT count(*) FROM vehicle_observation WHERE vehicle_trip_key IS NOT NULL").query(Integer.class).single()).isZero();
            connection.rollback();
            connection.setReadOnly(false);

            // when: 첫 묶음을 확정하고 두 번째 묶음의 처리는 롤백한다.
            var firstChunk = maintenance.applyChunk(version, until, 1);
            connection.commit();
            maintenance.applyChunk(version, until, 1);
            connection.rollback();

            // then
            assertThat(firstChunk).containsEntry("completed", false);
            assertThat(jdbc.sql("SELECT count(*) FROM vehicle_observation WHERE vehicle_trip_key IS NOT NULL").query(Integer.class).single()).isEqualTo(1);

            // when: 롤백한 두 번째 묶음부터 다시 처리해 완료한다.
            maintenance.applyChunk(version, until, 1);
            connection.commit();
            maintenance.applyChunk(version, until, 1);
            connection.commit();
            var completed = maintenance.applyChunk(version, until, 1);
            connection.commit();
            var repeated = maintenance.applyChunk(version, until, 1);

            // then
            assertThat(completed).containsEntry("completed", true);
            assertThat(repeated).containsEntry("processedBatches", 0);
            assertThat(jdbc.sql("SELECT count(*) FROM forecast_eligible_observation").query(Integer.class).single()).isEqualTo(1);
            assertThat(jdbc.sql("SELECT count(*) FROM vehicle_observation").query(Integer.class).single()).isEqualTo(3);
            assertThat(jdbc.sql("SELECT max(remaining_seats) FROM vehicle_observation").query(Integer.class).single()).isEqualTo(82);
            connection.rollback();
        }
    }
    @ParameterizedTest
    @ValueSource(ints = {1, 30})
    void 차량이_1대나_30대여도_계속되는_편도는_SQL_5회로_판정하고_제외_전환은_7회로_저장한다(int vehicles) throws Exception {
        // given: 쿼리 횟수는 합성 테스트의 JDBC 실행 수이며 운영 처리 시간을 뜻하지 않는다.
        try (var connection = connection()) {
            var jdbc = JdbcClient.create(new SingleConnectionDataSource(connection, true));
            long version = insertRoute(jdbc);
            long first = insertBatch(jdbc, version, 1, vehicles, false);
            long next = insertBatch(jdbc, version, 2, vehicles, false);
            long excluded = insertBatch(jdbc, version, 3, vehicles, true);
            var calls = new AtomicInteger();
            Connection counted = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("prepareStatement")) {
                        calls.incrementAndGet();
                    }
                    try {
                        return method.invoke(connection, arguments);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
            var quality = new TripQualityRepository(JdbcClient.create(new SingleConnectionDataSource(counted, true)));
            connection.setAutoCommit(false);
            quality.assessBatch(first);
            connection.commit();
            calls.set(0);

            // when
            quality.assessBatch(next);

            // then
            assertThat(calls.get()).isEqualTo(5);
            assertThat(jdbc.sql("SELECT count(*) FROM forecast_eligible_observation").query(Integer.class).single())
                .isEqualTo(vehicles * 2);
            connection.commit();
            calls.set(0);

            // when: 첫 차량에서만 82석을 발견한다.
            quality.assessBatch(excluded);

            // then: 앞선 관측까지 같은 편도를 제외하고 다른 차량은 유지한다.
            assertThat(calls.get()).isEqualTo(7);
            assertThat(jdbc.sql("SELECT count(*) FROM forecast_eligible_observation").query(Integer.class).single())
                .isEqualTo((vehicles - 1) * 3);
            assertThat(jdbc.sql("SELECT quality_revision FROM route").query(Long.class).single()).isEqualTo(2);
            connection.commit();
            calls.set(0);

            // when
            quality.assessBatch(excluded);

            // then: 이미 판정한 관측은 재저장하거나 버전을 올리지 않는다.
            assertThat(calls.get()).isEqualTo(1);
            assertThat(jdbc.sql("SELECT quality_revision FROM route").query(Long.class).single()).isEqualTo(2);
            connection.rollback();
        }
    }

    @Test
    void 예측_트랜잭션이_실패해도_별도_트랜잭션으로_저장한_편도_제외는_유지한다() throws Exception {
        // given
        var dataSource = new AbstractDataSource() {
            @Override
            public Connection getConnection() throws SQLException { return connection(); }
            @Override
            public Connection getConnection(String username, String password) throws SQLException { return connection(); }
        };
        var jdbc = JdbcClient.create(dataSource);
        long version = insertRoute(jdbc);
        long first = insertBatch(jdbc, version, 1, 1, false);
        long excluded = insertBatch(jdbc, version, 2, 1, true);
        var manager = new DataSourceTransactionManager(dataSource);
        var proxy = new ProxyFactory(new TripQualityRepository(jdbc));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        var quality = (TripQualityRepository) proxy.getProxy();
        quality.assessBatch(first);

        // when
        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(transaction -> {
            quality.assessBatch(excluded);
            jdbc.sql("UPDATE observation_batch SET forecast_completed_at = CURRENT_TIMESTAMP WHERE id = ?")
                .param(excluded).update();
            throw new IllegalStateException("계산 실패");
        })).isInstanceOf(IllegalStateException.class).hasMessage("계산 실패");

        // then
        assertThat(jdbc.sql("SELECT count(*) FROM forecast_eligible_observation").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT quality_revision FROM route").query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT forecast_completed_at IS NULL FROM observation_batch WHERE id = ?")
            .param(excluded).query(Boolean.class).single()).isTrue();
    }

    private long insertRoute(JdbcClient jdbc) {
        long route = jdbc.sql("""
            INSERT INTO route(public_route_id, source_id, source_route_id, display_name, start_stop_name, end_stop_name)
            VALUES ('900000010', 'TEST', '900000010', 'test', 'a', 'b') RETURNING id
            """).query(Long.class).single();
        long version = jdbc.sql("""
            INSERT INTO route_version(route_id, content_digest, valid_from, turn_sequence)
            VALUES (?, ?, '2026-09-20T00:00:00Z', 4) RETURNING id
            """).param(route).param("0".repeat(64)).query(Long.class).single();
        jdbc.sql("""
            INSERT INTO route_stop SELECT ?, n, 's' || n, 'stop' || n,
                CASE WHEN n <= 4 THEN 'UP' ELSE 'DOWN' END, true FROM generate_series(1, 7) n
            """).param(version).update();
        return version;
    }

    private long insertBatch(JdbcClient jdbc, long version, int step, int vehicles, boolean aboveRange) {
        var at = Instant.parse("2026-09-21T00:00:00Z").plusSeconds(step * 10).atOffset(ZoneOffset.UTC);
        long batch = jdbc.sql("""
            INSERT INTO observation_batch(route_version_id, scheduled_at, attempt_number, attempt_key,
                requested_at, response_received_at, outcome, normalization_version, collection_strategy_version)
            VALUES (?, ?, 1, ?, ?, ?, 'SUCCESS_ROWS', 'test', 'test') RETURNING id
            """).param(version).param(at).param("batch" + step).param(at).param(at).query(Long.class).single();
        jdbc.sql("""
            INSERT INTO vehicle_observation(observation_batch_id, route_version_id, source_row_number,
                vehicle_id, stop_order, stop_id, running_state, passed_stop_order, remaining_seats)
            SELECT ?, ?, n, 'bus-' || n, ?, ?, 2, ?, CASE WHEN ? AND n = 1 THEN 82 ELSE 44 END
            FROM generate_series(1, ?) n
            """).param(batch).param(version).param(step).param("s" + step).param(step)
                .param(aboveRange).param(vehicles).update();
        return batch;
    }

}
