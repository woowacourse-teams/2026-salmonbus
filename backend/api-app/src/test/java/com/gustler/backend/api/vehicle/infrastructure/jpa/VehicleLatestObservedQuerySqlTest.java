package com.gustler.backend.api.vehicle.infrastructure.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.vehicle.application.VehicleQueryRepository;
import com.gustler.backend.support.IntegrationTest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
@Import(VehicleLatestObservedQuerySqlTest.SqlCaptureConfig.class)
class VehicleLatestObservedQuerySqlTest {

    private static final String ROUTE_ID = "204000057";

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private VehicleQueryRepository repository;

    @Autowired
    private SqlCapture sqlCapture;

    @Test
    void 생성_SQL의_정상_조건이_리터럴이고_수신_시각순으로_정렬한다() {
        insertRouteWithSuccessfulBatch();
        sqlCapture.clear();

        repository.findLatestSnapshot(new RouteId(ROUTE_ID));

        String sql = sqlCapture.statements().stream()
            .filter(statement -> statement.contains("from observation_batch"))
            .findFirst()
            .orElseThrow();
        assertThat(sql)
            .contains("outcome in ('SUCCESS_ROWS','SUCCESS_EMPTY')")
            .contains("response_received_at is not null")
            .containsPattern("order by [^;]*response_received_at desc,[^;]*id desc");
    }

    private void insertRouteWithSuccessfulBatch() {
        OffsetDateTime observedAt = OffsetDateTime.parse("2026-08-27T12:00:00+09:00");
        long routeId = jdbcClient.sql("""
                INSERT INTO route (
                    public_route_id, source_id, source_route_id,
                    display_name, start_stop_name, end_stop_name
                ) VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id
                """)
            .params(ROUTE_ID, "GBIS", ROUTE_ID, "3330", "도촌동9단지앞", "안양역")
            .query(Long.class)
            .single();
        long routeVersionId = jdbcClient.sql("""
                INSERT INTO route_version (route_id, content_digest, valid_from)
                VALUES (?, ?, ?)
                RETURNING id
                """)
            .params(routeId, "5".repeat(64), observedAt.minusDays(1))
            .query(Long.class)
            .single();
        jdbcClient.sql("""
                INSERT INTO observation_batch (
                    route_version_id, scheduled_at, attempt_number, attempt_key,
                    requested_at, response_received_at, completed_at,
                    http_status, result_code, outcome, stored_rows,
                    normalization_version, collection_strategy_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
            .params(
                routeVersionId,
                observedAt.minusSeconds(2),
                1,
                UUID.randomUUID().toString(),
                observedAt.minusSeconds(1),
                observedAt,
                observedAt.plusNanos(1),
                200,
                0,
                "SUCCESS_EMPTY",
                0,
                "normalization-v1.0.0",
                "adaptive-kst-v1.0.1"
            )
            .update();
    }

    static final class SqlCapture implements StatementInspector {

        private final List<String> statements = new CopyOnWriteArrayList<>();

        @Override
        public String inspect(String sql) {
            statements.add(sql);
            return sql;
        }

        void clear() {
            statements.clear();
        }

        List<String> statements() {
            return List.copyOf(statements);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SqlCaptureConfig {

        @Bean
        SqlCapture vehicleSqlCapture() {
            return new SqlCapture();
        }

        @Bean
        HibernatePropertiesCustomizer vehicleSqlCaptureCustomizer(SqlCapture capture) {
            return properties -> properties.put(
                "hibernate.session_factory.statement_inspector",
                capture
            );
        }
    }
}
