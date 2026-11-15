package com.gustler.backend.forecasting.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.forecasting.api.forecast.MaintainPartitions;
import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import com.gustler.backend.support.TestDatabase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.PlatformTransactionManager;

@ForecastingIntegrationTest
class PartitionMaintenanceTest {
    /** 한국 시각 2030-01-01 23:59:59. 다른 시험의 날짜와 겹치지 않게 먼 날짜를 쓴다. */
    private static final Instant BEFORE_KOREAN_MIDNIGHT = Instant.parse("2030-01-01T14:59:59Z");
    private static final Instant AT_KOREAN_MIDNIGHT = Instant.parse("2030-01-01T15:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2030, 1, 1);

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private TestDatabase database;

    @AfterEach
    void 만든_날짜_파티션과_기본_파티션의_줄을_지운다() {
        for (String table : PartitionMaintenance.TABLES) {
            for (String partition : jdbcClient.sql("""
                    SELECT child.relname FROM pg_inherits
                    JOIN pg_class child ON child.oid = pg_inherits.inhrelid
                    WHERE inhparent = to_regclass(?) AND child.relname <> ?
                    """).params(table, table + "_default").query(String.class).list()) {
                jdbcClient.sql("DROP TABLE " + partition).update();
            }
            jdbcClient.sql("DELETE FROM " + table + "_default").update();
        }
        // 관측 테이블에는 마이그레이션이 만든 날짜 파티션이 있다. 시험이 만든 2030년 파티션만 지운다.
        for (String table : PartitionMaintenance.OBSERVATION_TABLES) {
            for (String partition : partitionsOf(table)) {
                if (partition.startsWith(table + "_p2030")) {
                    jdbcClient.sql("DROP TABLE " + partition).update();
                }
            }
        }
    }

    @Test
    void 오늘부터_사흘_뒤까지_없는_날짜_파티션을_테이블마다_만든다() {
        // given
        PartitionMaintenance maintenance = maintenanceAt(BEFORE_KOREAN_MIDNIGHT);

        // when
        MaintainPartitions.Report actual = maintenance.maintain();

        // then
        List<String> tables = new ArrayList<>(PartitionMaintenance.TABLES);
        tables.addAll(PartitionMaintenance.OBSERVATION_TABLES);
        assertSoftly(softly -> {
            softly.assertThat(maintenance.managedTables()).containsExactlyElementsOf(tables);
            softly.assertThat(actual.created()).hasSize(tables.size() * (PartitionMaintenance.DAYS_AHEAD + 1));
            softly.assertThat(actual.failed()).isEmpty();
            softly.assertThat(partitionsOf("stop_pass")).containsExactly("stop_pass_p20300101", "stop_pass_p20300102",
                "stop_pass_p20300103", "stop_pass_p20300104");
            softly.assertThat(partitionsOf("vehicle_observation")).contains("vehicle_observation_p20300101",
                "vehicle_observation_p20300102", "vehicle_observation_p20300103", "vehicle_observation_p20300104");
            softly.assertThat(actual.defaultRows()).containsOnlyKeys(tables)
                .allSatisfy((table, rows) -> assertThat(rows).isZero());
        });
    }

    @Test
    void 만든_파티션_수와_기본_파티션의_줄_수를_Prometheus_이름으로_내보낸다() {
        // given
        PrometheusMeterRegistry meters = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        PartitionMaintenance maintenance = maintenanceAt(BEFORE_KOREAN_MIDNIGHT);
        maintenance.setMeterRegistry(meters);

        // when
        maintenance.maintain();

        // then
        final int created = PartitionMaintenance.DAYS_AHEAD + 1;
        assertThat(meters.scrape()).contains(
            "salmonbus_partition_creations_total{table=\"forecast_run\"} " + created + ".0",
            "salmonbus_partition_default_rows{table=\"forecast_run\"} 0");
    }

    @Test
    void 기동_때_부르는_준비는_파티션만_만들고_기본_파티션의_줄_수는_세지_않는다() {
        // given
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        PartitionMaintenance maintenance = maintenanceAt(BEFORE_KOREAN_MIDNIGHT);
        maintenance.setMeterRegistry(meters);
        jdbcClient.sql("""
            INSERT INTO forecast_unresolved (source_date, vehicle_observation_id, target_stop_order, route_id, state,
                reason, closed_at)
            VALUES (DATE '2029-12-01', 1, 3, 7, 'LOST', 'PASS_NOT_CLOSED', now())
            """).update();

        // when
        MaintainPartitions.Report prepared = maintenance.prepare();
        final int gaugesAfterPrepare = meters.find("salmonbus.partition.default.rows").gauges().size();
        MaintainPartitions.Report maintained = maintenance.maintain();

        // then
        final int tables = PartitionMaintenance.TABLES.size() + PartitionMaintenance.OBSERVATION_TABLES.size();
        assertSoftly(softly -> {
            softly.assertThat(prepared.created()).hasSize(tables * (PartitionMaintenance.DAYS_AHEAD + 1));
            softly.assertThat(prepared.failed()).isEmpty();
            softly.assertThat(prepared.defaultRows()).isEmpty();
            softly.assertThat(gaugesAfterPrepare).isZero();
            softly.assertThat(maintained.created()).isEmpty();
            softly.assertThat(maintained.defaultRows()).containsEntry("forecast_unresolved", 1L);
        });
    }

    @Test
    void 이미_있는_파티션은_다시_만들지_않는다() {
        // given
        PartitionMaintenance maintenance = maintenanceAt(BEFORE_KOREAN_MIDNIGHT);
        maintenance.maintain();

        // when
        MaintainPartitions.Report actual = maintenance.maintain();

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.created()).isEmpty();
            softly.assertThat(actual.failed()).isEmpty();
            softly.assertThat(partitionsOf("forecast_run")).hasSize(PartitionMaintenance.DAYS_AHEAD + 1);
        });
    }

    @Test
    void 한국_자정을_지나면_만드는_범위가_하루_밀린다() {
        // when
        maintenanceAt(AT_KOREAN_MIDNIGHT).maintain();

        // then
        assertThat(partitionsOf("forecast_record")).containsExactly("forecast_record_p20300102",
            "forecast_record_p20300103", "forecast_record_p20300104", "forecast_record_p20300105");
    }

    @Test
    void 두_인스턴스가_같은_파티션을_동시에_만들어도_실패하지_않는다() throws Exception {
        // given
        PartitionMaintenance first = maintenanceAt(BEFORE_KOREAN_MIDNIGHT);
        PartitionMaintenance second = maintenanceAt(BEFORE_KOREAN_MIDNIGHT);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            // when
            CompletableFuture<List<PartitionMaintenance.Outcome>> one =
                CompletableFuture.supplyAsync(() -> ensureAll(first, start), executor);
            CompletableFuture<List<PartitionMaintenance.Outcome>> two =
                CompletableFuture.supplyAsync(() -> ensureAll(second, start), executor);
            start.countDown();
            List<PartitionMaintenance.Outcome> actual = new ArrayList<>(one.get(30, TimeUnit.SECONDS));
            actual.addAll(two.get(30, TimeUnit.SECONDS));

            // then
            // 뒤늦게 잠금을 얻은 쪽은 IF NOT EXISTS 로 건너뛰거나 "이미 있음"으로 실패한 뒤 다시 확인한다.
            // 어느 쪽도 실패가 아니다.
            assertSoftly(softly -> {
                softly.assertThat(actual).doesNotContain(PartitionMaintenance.Outcome.FAILED);
                softly.assertThat(PartitionMaintenance.TABLES).allSatisfy(table ->
                    assertThat(partitionsOf(table)).containsExactly(PartitionMaintenance.partitionName(table, DAY)));
            });
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void 기본_파티션에_그_날짜의_줄이_있으면_예외_없이_실패로_남기고_센다() {
        // given
        jdbcClient.sql("""
            INSERT INTO forecast_unresolved (source_date, vehicle_observation_id, target_stop_order, route_id, state,
                reason, closed_at)
            VALUES (?, 1, 5, 7, 'LOST', 'PASS_NOT_SEEN', now())
            """).param(DAY).update();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        PartitionMaintenance maintenance = maintenanceAt(BEFORE_KOREAN_MIDNIGHT);
        maintenance.setMeterRegistry(meters);

        // when
        MaintainPartitions.Report actual = maintenance.maintain();

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.failed()).containsExactly("forecast_unresolved_p20300101");
            softly.assertThat(actual.defaultRows()).containsEntry("forecast_unresolved", 1L);
            softly.assertThat(partitionsOf("forecast_unresolved")).containsExactly("forecast_unresolved_p20300102",
                "forecast_unresolved_p20300103", "forecast_unresolved_p20300104");
            softly.assertThat(meters.counter("salmonbus.partition.failures", "table", "forecast_unresolved",
                "reason", "default_rows").count()).isOne();
            softly.assertThat(meters.get("salmonbus.partition.default.rows")
                .tag("table", "forecast_unresolved").gauge().value()).isOne();
        });
    }

    @Test
    void 테이블_소유자가_아니면_예외_없이_실패로_남긴다() throws Exception {
        // given
        String role = "partition_writer_" + UUID.randomUUID().toString().replace("-", "");
        jdbcClient.sql("CREATE ROLE " + role).update();
        jdbcClient.sql("GRANT USAGE, CREATE ON SCHEMA public TO " + role).update();
        jdbcClient.sql("GRANT SELECT, INSERT ON ALL TABLES IN SCHEMA public TO " + role).update();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        try (Connection connection = database.connection()) {
            connection.createStatement().execute("SET ROLE " + role);
            SingleConnectionDataSource limited = new SingleConnectionDataSource(connection, true);
            PartitionMaintenance maintenance = new PartitionMaintenance(JdbcClient.create(limited),
                new DataSourceTransactionManager(limited), Clock.fixed(BEFORE_KOREAN_MIDNIGHT, ZoneOffset.UTC));
            maintenance.setMeterRegistry(meters);

            // when
            PartitionMaintenance.Outcome actual = maintenance.ensure("pass_label", DAY);

            // then
            assertSoftly(softly -> {
                softly.assertThat(actual).isEqualTo(PartitionMaintenance.Outcome.FAILED);
                softly.assertThat(partitionsOf("pass_label")).isEmpty();
                softly.assertThat(meters.counter("salmonbus.partition.failures", "table", "pass_label",
                    "reason", "permission").count()).isOne();
            });
        } finally {
            jdbcClient.sql("DROP OWNED BY " + role).update();
            jdbcClient.sql("DROP ROLE " + role).update();
        }
    }

    @Test
    void 관측_테이블이_일반_테이블이면_새_경로_스위치가_꺼진_수집_worker에서_아무것도_만들지_않는다() throws Exception {
        // given
        String schema = "observation_plain_" + UUID.randomUUID().toString().replace("-", "");
        jdbcClient.sql("CREATE SCHEMA " + schema).update();
        try (Connection connection = database.connection()) {
            connection.createStatement().execute("SET search_path = " + schema + ", public");
            for (String table : PartitionMaintenance.OBSERVATION_TABLES) {
                connection.createStatement().execute("CREATE TABLE " + table + " (id bigint NOT NULL)");
            }
            SingleConnectionDataSource scoped = new SingleConnectionDataSource(connection, true);
            PartitionMaintenance maintenance = new PartitionMaintenance(JdbcClient.create(scoped),
                new DataSourceTransactionManager(scoped), Clock.fixed(BEFORE_KOREAN_MIDNIGHT, ZoneOffset.UTC));
            maintenance.setForecastTables(false);

            // when
            MaintainPartitions.Report actual = maintenance.maintain();

            // then
            assertSoftly(softly -> {
                softly.assertThat(maintenance.managedTables()).isEmpty();
                softly.assertThat(actual.created()).isEmpty();
                softly.assertThat(actual.failed()).isEmpty();
                softly.assertThat(actual.defaultRows()).isEmpty();
                softly.assertThat(PartitionMaintenance.TABLES)
                    .allSatisfy(table -> assertThat(partitionsOf(table)).isEmpty());
                softly.assertThat(jdbcClient.sql("SELECT count(*) FROM pg_class WHERE relnamespace = ?::regnamespace")
                    .param(schema).query(Long.class).single()).isEqualTo(2);
            });
        } finally {
            jdbcClient.sql("DROP SCHEMA " + schema + " CASCADE").update();
        }
    }

    @Test
    void 관측_테이블이_파티션_테이블이면_스위치와_무관하게_수동_SQL과_같은_이름으로_날짜_파티션을_만든다() throws Exception {
        // given
        String schema = "observation_partition_" + UUID.randomUUID().toString().replace("-", "");
        jdbcClient.sql("CREATE SCHEMA " + schema).update();
        try (Connection connection = database.connection()) {
            connection.createStatement().execute("SET search_path = " + schema + ", public");
            for (String table : PartitionMaintenance.OBSERVATION_TABLES) {
                connection.createStatement().execute("CREATE TABLE " + table
                    + " (id bigint NOT NULL, source_date date NOT NULL) PARTITION BY RANGE (source_date)");
                connection.createStatement().execute("CREATE TABLE " + table + "_default PARTITION OF " + table
                    + " DEFAULT");
            }
            connection.createStatement()
                .execute("INSERT INTO vehicle_observation_default VALUES (1, DATE '2029-12-31')");
            SingleConnectionDataSource scoped = new SingleConnectionDataSource(connection, true);
            PartitionMaintenance maintenance = new PartitionMaintenance(JdbcClient.create(scoped),
                new DataSourceTransactionManager(scoped), Clock.fixed(BEFORE_KOREAN_MIDNIGHT, ZoneOffset.UTC));
            maintenance.setForecastTables(false);

            // when
            MaintainPartitions.Report actual = maintenance.maintain();

            // then
            assertSoftly(softly -> {
                softly.assertThat(maintenance.managedTables())
                    .containsExactlyElementsOf(PartitionMaintenance.OBSERVATION_TABLES);
                softly.assertThat(actual.created()).containsExactly("observation_batch_p20300101",
                    "observation_batch_p20300102", "observation_batch_p20300103", "observation_batch_p20300104",
                    "vehicle_observation_p20300101", "vehicle_observation_p20300102", "vehicle_observation_p20300103",
                    "vehicle_observation_p20300104");
                softly.assertThat(actual.failed()).isEmpty();
                softly.assertThat(actual.defaultRows()).containsExactlyInAnyOrderEntriesOf(
                    Map.of("observation_batch", 0L, "vehicle_observation", 1L));
                softly.assertThat(PartitionMaintenance.TABLES)
                    .allSatisfy(table -> assertThat(partitionsOf(table)).isEmpty());
            });
        } finally {
            jdbcClient.sql("DROP SCHEMA " + schema + " CASCADE").update();
        }
    }

    private List<PartitionMaintenance.Outcome> ensureAll(PartitionMaintenance maintenance, CountDownLatch start) {
        try {
            start.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
        return PartitionMaintenance.TABLES.stream().map(table -> maintenance.ensure(table, DAY)).toList();
    }

    private PartitionMaintenance maintenanceAt(Instant now) {
        return new PartitionMaintenance(jdbcClient, transactionManager, Clock.fixed(now, ZoneOffset.UTC));
    }

    private List<String> partitionsOf(String table) {
        return jdbcClient.sql("""
                SELECT child.relname FROM pg_inherits
                JOIN pg_class child ON child.oid = pg_inherits.inhrelid
                WHERE inhparent = to_regclass(?) AND child.relname <> ?
                ORDER BY child.relname
                """)
            .params(table, table + "_default")
            .query(String.class)
            .list();
    }
}
