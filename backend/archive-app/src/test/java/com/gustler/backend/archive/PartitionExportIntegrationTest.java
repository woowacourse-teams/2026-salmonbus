package com.gustler.backend.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.support.TestDatabase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;

/** 공유 PG 서버의 격리 DB 에서 날짜 파티션 내보내기·확인·떼어 지우기를 실제 파티션 테이블로 검증한다. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PartitionExportIntegrationTest {
    static final String PREFIX = "salmonbus/test/lake-v3/";
    static final LocalDate DAY = LocalDate.parse("2026-11-12");
    static final String EDGE_DOUBLES =
        "{0.1,0.3333333333333333,5e-324,1.7976931348623157e308,NaN,Infinity,-Infinity,NULL,0.30000000000000004}";
    TestDatabase database;
    DriverManagerDataSource source;
    JdbcClient jdbc;
    Clock clock = Clock.fixed(Instant.parse("2026-11-16T03:00:00Z"), ZoneOffset.UTC);

    @BeforeAll
    void migrate() {
        database = TestDatabase.create();
        source = new DriverManagerDataSource(database.jdbcUrl(), database.username(), database.password());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = JdbcClient.create(source);
    }

    @AfterAll
    void close() {
        if (database != null) {
            database.close();
        }
    }

    @BeforeEach
    void reset() {
        jdbc.sql("""
            DO $$ DECLARE r record; BEGIN
                FOR r IN SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
                    WHERE c.relname ~ '_p[0-9]{8}$' LOOP
                    EXECUTE format('DROP TABLE %I', r.relname);
                END LOOP;
                FOR r IN SELECT tablename FROM pg_tables WHERE tablename LIKE 'restored_%' OR tablename LIKE 'copy_%' LOOP
                    EXECUTE format('DROP TABLE %I', r.tablename);
                END LOOP;
            END $$
            """).update();
        jdbc.sql("TRUNCATE forecast_run, forecast_record, stop_pass, pass_label, forecast_unresolved, daily_export")
            .update();
        clock = Clock.fixed(Instant.parse("2026-11-16T03:00:00Z"), ZoneOffset.UTC);
    }

    PartitionExportService service(ArchiveObjects objects, final boolean dropEnabled) {
        return service(objects, dropEnabled, 1000, new SimpleMeterRegistry(), source);
    }

    PartitionExportService service(
        ArchiveObjects objects,
        final boolean dropEnabled,
        final int pageSize,
        SimpleMeterRegistry metrics,
        DriverManagerDataSource dataSource
    ) {
        PartitionExportSettings settings = new PartitionExportSettings(dropEnabled,
            PartitionExportSettings.MIN_RETENTION_DAYS, PREFIX, Duration.ofHours(1), pageSize, Duration.ofMillis(300));
        PartitionExportRepository repository = new PartitionExportRepository(JdbcClient.create(dataSource),
            new JdbcTransactionManager(dataSource), settings.lockTimeout());
        return new PartitionExportService(repository, objects, clock, metrics, settings);
    }

    void partition(String table, LocalDate date) {
        jdbc.sql("CREATE TABLE " + table + "_p" + date.toString().replace("-", "") + " PARTITION OF " + table
            + " FOR VALUES FROM ('" + date + "') TO ('" + date.plusDays(1) + "')").update();
    }

    void forecastRecord(LocalDate date, final long observation, String doubles) {
        jdbc.sql("""
            INSERT INTO forecast_record (source_date, vehicle_observation_id, observation_batch_id, source_row_number, route_id,
                route_version_id, vehicle_id, passed_stop_order, trip_start_observation_id, chain_start_observation_id, down,
                capacity, extended, target_stop_orders, expected_seats, full_probabilities)
            VALUES (?, ?, 9223372036854775807, 1, 1, 2, 'vehicle', 3, 100, 101, true, 45,
                false, (SELECT array_agg(i) FROM generate_series(1, cardinality(CAST(? AS float8[]))) i),
                CAST(? AS float8[]), CAST(? AS float8[]))
            """).params(date, observation, doubles, doubles, doubles).update();
    }

    void forecastRun(LocalDate date, final long batch) {
        jdbc.sql("""
            INSERT INTO forecast_run (source_date, observation_batch_id, route_id, route_version_id, received_at, confirmed_at,
                model_deployment_id, outcome, prediction_count, source_incomplete, counted_rows)
            VALUES (?, ?, 1, 2, '2026-11-12 03:00:00.123456+00', '2026-11-12 03:00:01.000001+00',
                NULL, 'PUBLISHED', 3, false, 3)
            """).params(date, batch).update();
    }

    void passLabel(LocalDate date, final int stop) {
        jdbc.sql("""
            INSERT INTO pass_label (source_date, route_id, trip_start_observation_id, stop_order, capacity, label_version,
                model_deployment_id, state, reason, label_value, previous_last_observation_id,
                previous_closure_observation_id, parameter_hash, computed_at)
            VALUES (?, 1, 100, ?, 45, 'v1', 11, 'UNSUPPORTED', 'NO_ARRIVAL', NULL, NULL, 7,
                NULL, '2026-11-12 23:59:59.999999+00')
            """).params(date, stop).update();
        jdbc.sql("""
            INSERT INTO pass_label (source_date, route_id, trip_start_observation_id, stop_order, capacity, label_version,
                model_deployment_id, state, reason, label_value, previous_last_observation_id,
                previous_closure_observation_id, parameter_hash, computed_at)
            VALUES (?, 1, 100, ?, 45, 'v2', 11, 'SETTLED', NULL, 0.1, 5, 6, ?, '2026-11-12 15:00:00+00')
            """).params(date, stop, "a".repeat(64)).update();
    }

    long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    static List<Path> filesUnder(Path directory) {
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile).toList();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    boolean exists(String relation) {
        return jdbc.sql("SELECT to_regclass(?) IS NOT NULL").param(relation).query(Boolean.class).single();
    }

    Optional<Map<String, Object>> ledger(String table, LocalDate date) {
        return jdbc.sql("SELECT * FROM daily_export WHERE table_name = ? AND source_date = ?").params(table, date)
            .query().listOfRows().stream().findFirst();
    }

    /** 두 테이블의 줄이 줄 단위로, 그리고 텍스트 표기까지 같은지 본다. */
    void assertSameRows(String original, String restored) {
        assertSoftly(softly -> {
            softly.assertThat(count("SELECT count(*) FROM ((SELECT * FROM " + original + " EXCEPT ALL SELECT * FROM "
                + restored + ") UNION ALL (SELECT * FROM " + restored + " EXCEPT ALL SELECT * FROM " + original
                + ")) d")).isZero();
            softly.assertThat(jdbc.sql("SELECT string_agg(t::text, E'\\n' ORDER BY t::text) FROM " + restored + " t")
                .query(String.class).single()).isEqualTo(jdbc.sql("SELECT string_agg(t::text, E'\\n' ORDER BY t::text)"
                + " FROM " + original + " t").query(String.class).single());
        });
    }

    @Test
    void 파티션_하나를_내보내고_S3_파일만으로_배열_시각_NULL까지_같은_줄을_되살린다() {
        // given
        partition("forecast_record", DAY);
        partition("forecast_run", DAY);
        partition("pass_label", DAY);
        forecastRecord(DAY, 1, EDGE_DOUBLES);
        forecastRecord(DAY, 2, "{0.7302,1e-7,123456789.12345679}");
        forecastRun(DAY, 9223372036854775806L);
        passLabel(DAY, 4);
        MemoryObjects objects = new MemoryObjects();

        // when
        PartitionExportService.RunReport report = service(objects, false).run();

        // then
        assertThat(report.exported()).isEqualTo(3);
        Map<String, Object> ledger = ledger("forecast_record", DAY).orElseThrow();
        String key = (String) ledger.get("object_key");
        String sha = ((String) ledger.get("sha256")).trim();
        PartitionManifest manifest = PartitionManifest.parse(objects.values.get(key));
        assertSoftly(softly -> {
            softly.assertThat(key).startsWith(PREFIX + "forecast_record/source_date=2026-11-12/manifest-")
                .endsWith(sha + ".json");
            softly.assertThat(ArchiveFiles.digest(objects.values.get(key))).isEqualTo(sha);
            softly.assertThat(ledger.get("row_count")).isEqualTo(2L);
            softly.assertThat(ledger.get("verified_at")).isNotNull();
            softly.assertThat(ledger.get("dropped_at")).isNull();
            softly.assertThat(manifest.format()).isEqualTo("salmonbus-partition-v1");
            softly.assertThat(manifest.partition()).isEqualTo("forecast_record_p20261112");
            softly.assertThat(manifest.rows()).isEqualTo(2);
            softly.assertThat(manifest.orderBy()).containsExactly("source_date", "vehicle_observation_id");
            softly.assertThat(manifest.columns()).contains(new PartitionManifest.Column("expected_seats",
                "double precision[]"), new PartitionManifest.Column("vehicle_id", "character varying(40)"));
            softly.assertThat(manifest.files()).singleElement().satisfies(file -> {
                assertThat(file.rows()).isEqualTo(2);
                assertThat(file.key()).isEqualTo(PREFIX + "forecast_record/source_date=2026-11-12/000000-"
                    + file.sha256() + ".jsonl.gz");
                assertThat(ArchiveFiles.digest(objects.values.get(file.key()))).isEqualTo(file.sha256());
            });
        });
        assertThat(PartitionRestore.restore(jdbc, objects, key, sha, "restored_record")).isEqualTo(2);
        assertSameRows("forecast_record_p20261112", "restored_record");
        assertThat(jdbc.sql("SELECT expected_seats::text FROM restored_record WHERE vehicle_observation_id = 1")
            .query(String.class).single())
            .isEqualTo("{0.1,0.3333333333333333,5e-324,1.7976931348623157e+308,NaN,Infinity,-Infinity,NULL,"
                + "0.30000000000000004}");
        for (String table : List.of("forecast_run", "pass_label")) {
            Map<String, Object> other = ledger(table, DAY).orElseThrow();
            PartitionRestore.restore(jdbc, objects, (String) other.get("object_key"),
                ((String) other.get("sha256")).trim(), "restored_" + table);
            assertSameRows(table + "_p20261112", "restored_" + table);
        }
    }

    @Test
    void 큰_파티션은_파일을_나누고_페이지를_넘겨도_줄을_빠뜨리지_않는다() {
        // given
        partition("forecast_record", DAY);
        jdbc.sql("""
            INSERT INTO forecast_record (source_date, vehicle_observation_id, observation_batch_id, source_row_number, route_id,
                route_version_id, vehicle_id, passed_stop_order, trip_start_observation_id, chain_start_observation_id, down,
                capacity, extended, target_stop_orders, expected_seats, full_probabilities)
            SELECT '2026-11-12', g, 10, 1, 1, 2, 'vehicle', 3, 100, 100, true, 45, false, a.targets, a.seats, a.seats
            FROM generate_series(1, 400) g CROSS JOIN LATERAL (
                SELECT array_agg(i) AS targets, array_agg(random() * i + g) AS seats FROM generate_series(1, 1000) i) a
            """).update();
        MemoryObjects objects = new MemoryObjects();

        // when
        service(objects, false, 7, new SimpleMeterRegistry(), source).run();

        // then
        Map<String, Object> ledger = ledger("forecast_record", DAY).orElseThrow();
        PartitionManifest manifest = PartitionManifest.parse(objects.values.get((String) ledger.get("object_key")));
        assertSoftly(softly -> {
            softly.assertThat(manifest.files()).hasSizeGreaterThan(1);
            softly.assertThat(manifest.files())
                .allSatisfy(file -> assertThat(file.bytes()).isLessThan(ArchiveFiles.MAX_BYTES));
            softly.assertThat(manifest.files().stream().mapToLong(PartitionManifest.FileEntry::rows).sum())
                .isEqualTo(400);
        });
        PartitionRestore.restore(jdbc, objects, (String) ledger.get("object_key"),
            ((String) ledger.get("sha256")).trim(), "restored_large");
        assertSameRows("forecast_record_p20261112", "restored_large");
    }

    @Test
    void 한국_날짜로_오늘_포함_나흘은_남기고_기본_파티션은_내보내지_않는다() {
        // given
        partition("forecast_run", LocalDate.parse("2026-11-12"));
        partition("forecast_run", LocalDate.parse("2026-11-13"));
        jdbc.sql("""
            INSERT INTO forecast_run (source_date, observation_batch_id, route_id, route_version_id, received_at,
                confirmed_at, model_deployment_id, outcome, prediction_count, source_incomplete, counted_rows)
            VALUES ('2026-11-01', 1, 1, 2, now(), now(), NULL, 'EMPTY', 0, false, 0)
            """).update();
        MemoryObjects objects = new MemoryObjects();
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        clock = Clock.fixed(Instant.parse("2026-11-16T14:59:59Z"), ZoneOffset.UTC);

        // when
        service(objects, false, 1000, metrics, source).run();

        // then
        assertSoftly(softly -> {
            softly.assertThat(jdbc.sql("SELECT source_date FROM daily_export").query(LocalDate.class).list())
                .containsExactly(LocalDate.parse("2026-11-12"));
            softly.assertThat(metrics.get("salmonbus_archive_partition_default_rows").tag("table", "forecast_run")
                .gauge().value()).isEqualTo(1.0);
        });

        // when
        clock = Clock.fixed(Instant.parse("2026-11-16T15:00:00Z"), ZoneOffset.UTC);
        service(objects, false).run();

        // then
        assertSoftly(softly -> {
            softly.assertThat(jdbc.sql("SELECT source_date FROM daily_export ORDER BY source_date")
                .query(LocalDate.class).list())
                .containsExactly(LocalDate.parse("2026-11-12"), LocalDate.parse("2026-11-13"));
            softly.assertThat(count("SELECT count(*) FROM forecast_run_default")).isOne();
        });
    }

    @Test
    void 오래된_날짜부터_같은_날짜는_테이블_순서로_처리한다() {
        // given
        partition("forecast_record", LocalDate.parse("2026-11-12"));
        partition("stop_pass", LocalDate.parse("2026-11-10"));
        partition("forecast_run", LocalDate.parse("2026-11-11"));
        partition("forecast_run", LocalDate.parse("2026-11-10"));
        partition("forecast_run", LocalDate.parse("2026-11-13"));
        MemoryObjects objects = new MemoryObjects();

        // when
        service(objects, false).run();

        // then
        assertThat(objects.manifestRoots()).containsExactly("forecast_run/source_date=2026-11-10",
            "stop_pass/source_date=2026-11-10", "forecast_run/source_date=2026-11-11",
            "forecast_record/source_date=2026-11-12");
    }

    @Test
    void 이미_내보낸_날짜는_다시_올리지_않는다() {
        // given
        partition("forecast_run", DAY);
        forecastRun(DAY, 1);
        MemoryObjects objects = new MemoryObjects();
        service(objects, false).run();
        final int puts = objects.puts.size();
        Object exportedAt = ledger("forecast_run", DAY).orElseThrow().get("exported_at");

        // when
        PartitionExportService.RunReport report = service(objects, false).run();

        // then
        assertSoftly(softly -> {
            softly.assertThat(report.exported()).isZero();
            softly.assertThat(report.pending()).isZero();
            softly.assertThat(objects.puts).hasSize(puts);
            softly.assertThat(ledger("forecast_run", DAY).orElseThrow().get("exported_at")).isEqualTo(exportedAt);
        });
    }

    @Test
    void 업로드가_중간에_실패하면_장부를_남기지_않고_다음_회차에_다시_올린다() {
        // given
        partition("forecast_run", DAY);
        forecastRun(DAY, 1);
        MemoryObjects objects = new MemoryObjects();
        objects.failManifestOnce = true;

        // when
        PartitionExportService.RunReport failed = service(objects, true).run();

        // then
        assertSoftly(softly -> {
            softly.assertThat(failed.pending()).isOne();
            softly.assertThat(failed.oldestPending()).isEqualTo(DAY);
            softly.assertThat(ledger("forecast_run", DAY)).isEmpty();
            softly.assertThat(exists("forecast_run_p20261112")).isTrue();
        });

        // when
        PartitionExportService.RunReport retried = service(objects, true).run();

        // then
        assertSoftly(softly -> {
            softly.assertThat(retried.dropped()).isOne();
            softly.assertThat(retried.oldestPending()).isNull();
            softly.assertThat(exists("forecast_run_p20261112")).isFalse();
            softly.assertThat(count("SELECT count(*) FROM daily_export WHERE dropped_at IS NOT NULL")).isOne();
        });
    }

    @Test
    void 확인을_마친_파티션을_떼어_지우고_S3_파일만으로_되살린다() {
        // given
        partition("forecast_record", DAY);
        forecastRecord(DAY, 1, EDGE_DOUBLES);
        forecastRecord(DAY, 2, "{1,2}");
        jdbc.sql("CREATE TABLE copy_record AS SELECT * FROM forecast_record_p20261112").update();
        MemoryObjects objects = new MemoryObjects();

        // when
        PartitionExportService.RunReport report = service(objects, true).run();

        // then
        Map<String, Object> ledger = ledger("forecast_record", DAY).orElseThrow();
        assertSoftly(softly -> {
            softly.assertThat(report.dropped()).isOne();
            softly.assertThat(exists("forecast_record_p20261112")).isFalse();
            softly.assertThat(count("SELECT count(*) FROM forecast_record")).isZero();
            softly.assertThat(ledger.get("dropped_at")).isNotNull();
        });
        PartitionRestore.restore(jdbc, objects, (String) ledger.get("object_key"),
            ((String) ledger.get("sha256")).trim(), "restored_dropped");
        assertSameRows("copy_record", "restored_dropped");
    }

    @Test
    void 기본_파티션에_든_줄은_수동_SQL로_제_날짜_파티션에_옮긴_뒤에야_내보내고_S3_파일만으로_옮기기_전과_같게_되살린다()
        throws Exception {
        // given
        List<String> tables = List.of("forecast_record", "forecast_run");
        forecastRecord(DAY, 1, EDGE_DOUBLES);
        forecastRecord(DAY, 2, "{0.7302,1e-7,123456789.12345679}");
        forecastRun(DAY, 7);
        for (String table : tables) {
            jdbc.sql("CREATE TABLE copy_" + table + " AS SELECT * FROM " + table + "_default").update();
        }
        MemoryObjects objects = new MemoryObjects();

        // when
        PartitionExportService.RunReport before = service(objects, true).run();

        // then
        assertSoftly(softly -> {
            softly.assertThat(before.exported()).isZero();
            softly.assertThat(count("SELECT count(*) FROM daily_export")).isZero();
            softly.assertThat(objects.values).isEmpty();
            softly.assertThat(count("SELECT count(*) FROM forecast_record_default")).isEqualTo(2);
        });

        // when
        try (Connection connection = database.connection()) {
            for (String table : tables) {
                ObservationPartitionExportIntegrationTest.execute(connection,
                    "SET salmonbus.default_rows_table = '" + table + "'");
                ObservationPartitionExportIntegrationTest.run(connection, "move-partition-default-rows.sql");
            }
        }
        PartitionExportService.RunReport actual = service(objects, true).run();

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.exported()).isEqualTo(2);
            softly.assertThat(actual.dropped()).isEqualTo(2);
            softly.assertThat(count("SELECT count(*) FROM forecast_record")).isZero();
            softly.assertThat(count("SELECT count(*) FROM forecast_run")).isZero();
        });
        for (String table : tables) {
            Map<String, Object> ledger = ledger(table, DAY).orElseThrow();
            assertThat(ledger.get("dropped_at")).as(table).isNotNull();
            PartitionRestore.restore(jdbc, objects, (String) ledger.get("object_key"),
                ((String) ledger.get("sha256")).trim(), "restored_" + table);
            assertSameRows("copy_" + table, "restored_" + table);
        }
    }

    @Test
    void 지우기가_꺼져_있으면_내보내고_확인만_한다() {
        // given
        partition("forecast_run", DAY);
        forecastRun(DAY, 1);
        MemoryObjects objects = new MemoryObjects();

        // when
        service(objects, false).run();

        // then
        assertSoftly(softly -> {
            softly.assertThat(exists("forecast_run_p20261112")).isTrue();
            softly.assertThat(count("SELECT count(*) FROM forecast_run_p20261112")).isOne();
            softly.assertThat(ledger("forecast_run", DAY).orElseThrow().get("verified_at")).isNotNull();
            softly.assertThat(ledger("forecast_run", DAY).orElseThrow().get("dropped_at")).isNull();
            softly.assertThat(objects.released).isEmpty();
        });
    }

    @Test
    void 떼어_지운_날짜만_저장소에_알린다() {
        // given
        partition("forecast_run", DAY);
        partition("forecast_run", DAY.plusDays(1));
        forecastRun(DAY, 1);
        forecastRun(DAY.plusDays(1), 2);
        MemoryObjects objects = new MemoryObjects();

        // when
        service(objects, true).run();

        // then
        assertSoftly(softly -> {
            softly.assertThat(exists("forecast_run_p20261112")).isFalse();
            softly.assertThat(exists("forecast_run_p20261113")).isTrue();
            softly.assertThat(objects.released).containsExactly(PREFIX + "forecast_run/source_date=2026-11-12/");
        });
    }

    @Test
    void 저장소가_파일을_치우지_못해도_떼어_지운_결과는_그대로다() {
        // given
        partition("forecast_run", DAY);
        forecastRun(DAY, 1);
        MemoryObjects objects = new MemoryObjects();
        objects.failRelease = true;

        // when
        PartitionExportService.RunReport report = service(objects, true).run();

        // then
        assertSoftly(softly -> {
            softly.assertThat(report.dropped()).isOne();
            softly.assertThat(exists("forecast_run_p20261112")).isFalse();
            softly.assertThat(ledger("forecast_run", DAY).orElseThrow().get("dropped_at")).isNotNull();
        });
    }

    @Test
    void 폴더_저장소로도_같은_순서로_내보내고_떼어_지운_날짜의_파일만_치운다(@TempDir Path directory) {
        // given
        LocalDate earlier = DAY.minusDays(1);
        partition("forecast_record", DAY);
        partition("forecast_run", earlier);
        forecastRecord(DAY, 1, EDGE_DOUBLES);
        forecastRecord(DAY, 2, "{1,2}");
        forecastRun(earlier, 3);
        jdbc.sql("CREATE TABLE copy_record AS SELECT * FROM forecast_record_p20261112").update();
        DirectoryArchiveObjects objects = new DirectoryArchiveObjects(directory);

        // when
        service(objects, false).run();
        Map<String, Object> exported = ledger("forecast_record", DAY).orElseThrow();
        PartitionRestore.restore(jdbc, objects, (String) exported.get("object_key"),
            ((String) exported.get("sha256")).trim(), "restored_directory");
        forecastRun(earlier, 4);
        PartitionExportService.RunReport report = service(objects, true).run();

        // then
        assertSameRows("copy_record", "restored_directory");
        assertSoftly(softly -> {
            softly.assertThat(report.dropped()).isOne();
            softly.assertThat(exists("forecast_record_p20261112")).isFalse();
            softly.assertThat(ledger("forecast_record", DAY).orElseThrow().get("dropped_at")).isNotNull();
            softly.assertThat(directory.resolve(PREFIX + "forecast_record/source_date=2026-11-12")).doesNotExist();
            softly.assertThat(exists("forecast_run_p20261111")).isTrue();
            softly.assertThat(filesUnder(directory.resolve(PREFIX + "forecast_run/source_date=2026-11-11")))
                .hasSize(2);
        });
    }

    @Test
    void 내보낸_뒤_줄이_늘면_지우지_않는다() {
        // given
        partition("forecast_run", DAY);
        forecastRun(DAY, 1);
        MemoryObjects objects = new MemoryObjects();
        service(objects, false).run();
        forecastRun(DAY, 2);
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();

        // when
        PartitionExportService.RunReport report = service(objects, true, 1000, metrics, source).run();

        // then
        assertSoftly(softly -> {
            softly.assertThat(report.dropped()).isZero();
            softly.assertThat(report.oldestPending()).isEqualTo(DAY);
            softly.assertThat(count("SELECT count(*) FROM forecast_run_p20261112")).isEqualTo(2);
            softly.assertThat(ledger("forecast_run", DAY).orElseThrow().get("dropped_at")).isNull();
            softly.assertThat(metrics.get("salmonbus_archive_partition_failures").tag("reason", "ROW_COUNT_CHANGED")
                .counter().count()).isOne();
        });
    }

    @Test
    void S3_파일이_장부와_다르면_지우지_않는다() {
        // given
        partition("forecast_run", DAY);
        forecastRun(DAY, 1);
        MemoryObjects objects = new MemoryObjects();
        service(objects, false).run();
        String file = objects.values.keySet().stream()
            .filter(k -> k.endsWith(".jsonl.gz"))
            .findFirst()
            .orElseThrow();
        objects.values.put(file, ArchiveFiles.encode(List.of("{\"changed\":true}")));

        // when
        PartitionExportService.RunReport report = service(objects, true).run();

        // then
        assertSoftly(softly -> {
            softly.assertThat(report.dropped()).isZero();
            softly.assertThat(exists("forecast_run_p20261112")).isTrue();
            softly.assertThat(ledger("forecast_run", DAY).orElseThrow().get("dropped_at")).isNull();
        });
    }

    @Test
    void 같은_키에_다른_내용의_파일이_이미_있으면_장부에_적지_않고_줄을_지우지_않는다() {
        // given
        partition("forecast_run", DAY);
        forecastRun(DAY, 1);
        MemoryObjects earlier = new MemoryObjects();
        service(earlier, false).run();
        String file = earlier.values.keySet().stream()
            .filter(k -> k.endsWith(".jsonl.gz"))
            .findFirst()
            .orElseThrow();
        jdbc.sql("TRUNCATE daily_export").update();
        MemoryObjects objects = new MemoryObjects();
        objects.values.put(file, ArchiveFiles.encode(List.of("{\"changed\":true}")));
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();

        // when
        PartitionExportService.RunReport report = service(objects, true, 1000, metrics, source).run();

        // then
        assertSoftly(softly -> {
            softly.assertThat(report.exported()).isZero();
            softly.assertThat(report.dropped()).isZero();
            softly.assertThat(report.oldestPending()).isEqualTo(DAY);
            softly.assertThat(ledger("forecast_run", DAY)).isEmpty();
            softly.assertThat(exists("forecast_run_p20261112")).isTrue();
            softly.assertThat(count("SELECT count(*) FROM forecast_run_p20261112")).isOne();
            softly.assertThat(metrics.get("salmonbus_archive_partition_failures").tag("table", "forecast_run")
                .tag("reason", "FILE_HASH_MISMATCH").counter().count()).isOne();
        });
    }

    @Test
    void manifest_해시가_장부와_다르면_지우지_않는다() {
        // given
        partition("forecast_run", DAY);
        forecastRun(DAY, 1);
        MemoryObjects objects = new MemoryObjects();
        service(objects, false).run();
        jdbc.sql("UPDATE daily_export SET sha256 = ?").param("0".repeat(64)).update();

        // when
        PartitionExportService.RunReport report = service(objects, true).run();

        // then
        assertSoftly(softly -> {
            softly.assertThat(report.dropped()).isZero();
            softly.assertThat(exists("forecast_run_p20261112")).isTrue();
        });
    }

    @Test
    void 확인_시각이_없는_장부는_다시_읽어_확인한_뒤에만_지운다() {
        // given
        partition("forecast_run", DAY);
        forecastRun(DAY, 1);
        MemoryObjects objects = new MemoryObjects();
        service(objects, false).run();
        jdbc.sql("UPDATE daily_export SET verified_at = NULL").update();
        String manifest = (String) ledger("forecast_run", DAY).orElseThrow().get("object_key");
        byte[] original = objects.values.remove(manifest);

        // when
        service(objects, true).run();

        // then
        assertSoftly(softly -> {
            softly.assertThat(exists("forecast_run_p20261112")).isTrue();
            softly.assertThat(ledger("forecast_run", DAY).orElseThrow().get("verified_at")).isNull();
        });

        // when
        objects.values.put(manifest, original);
        service(objects, true).run();

        // then
        assertThat(exists("forecast_run_p20261112")).isFalse();
    }

    @Test
    void 다른_실행이_장부에_다른_내용을_먼저_적으면_실패로_남기고_지우지_않는다() {
        // given
        partition("forecast_run", DAY);
        forecastRun(DAY, 1);
        MemoryObjects objects = new MemoryObjects();
        PartitionExportSettings settings = new PartitionExportSettings(true, 4, PREFIX, Duration.ofHours(1), 1000,
            Duration.ofMillis(300));
        byte[] otherManifest = new PartitionManifest(PartitionManifest.FORMAT, "forecast_run", DAY.toString(),
            "forecast_run_p20261112", 0, List.of(), List.of(), "2026-11-15T00:00:00Z", List.of()).toBytes();
        String otherKey = PREFIX + "forecast_run/source_date=2026-11-12/manifest-other.json";
        objects.values.put(otherKey, otherManifest);
        PartitionExportRepository racing = new PartitionExportRepository(jdbc, new JdbcTransactionManager(source),
            settings.lockTimeout()) {
            @Override
            public PartitionExportRepository.LedgerWrite recordVerified(
                PartitionExportRepository.Partition partition,
                PartitionExportRepository.Ledger ledger
            ) {
                jdbc.sql("""
                    INSERT INTO daily_export(table_name, source_date, object_key, row_count, sha256, exported_at,
                        verified_at) VALUES ('forecast_run', '2026-11-12', ?, 0, ?, now(), now())
                    """).params(otherKey, ArchiveFiles.digest(otherManifest)).update();
                return super.recordVerified(partition, ledger);
            }
        };
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();

        // when
        PartitionExportService.RunReport report = new PartitionExportService(racing, objects, clock, metrics, settings)
            .run();

        // then
        assertSoftly(softly -> {
            softly.assertThat(report.dropped()).isZero();
            softly.assertThat(exists("forecast_run_p20261112")).isTrue();
            softly.assertThat(ledger("forecast_run", DAY).orElseThrow().get("object_key")).isEqualTo(otherKey);
            softly.assertThat(metrics.get("salmonbus_archive_partition_failures")
                .tag("reason", "EXPORT_CONTENT_MISMATCH").counter().count()).isOne();
        });
    }

    @Test
    void 잠금을_얻지_못하면_지우지_않고_다음_회차에_지운다() throws Exception {
        // given
        partition("forecast_run", DAY);
        forecastRun(DAY, 1);
        MemoryObjects objects = new MemoryObjects();
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        PartitionExportService.RunReport blocked;
        try (Connection reader = database.connection(); Statement statement = reader.createStatement()) {
            reader.setAutoCommit(false);
            statement.execute("SELECT count(*) FROM forecast_run");

            // when
            blocked = service(objects, true, 1000, metrics, source).run();

            reader.rollback();
        }

        // then
        assertSoftly(softly -> {
            softly.assertThat(blocked.dropped()).isZero();
            softly.assertThat(exists("forecast_run_p20261112")).isTrue();
            softly.assertThat(ledger("forecast_run", DAY).orElseThrow().get("dropped_at")).isNull();
            softly.assertThat(metrics.get("salmonbus_archive_partition_deferrals").tag("reason", "LOCK_TIMEOUT")
                .counter().count()).isOne();
        });

        // when
        PartitionExportService.RunReport retried = service(objects, true).run();

        // then
        assertSoftly(softly -> {
            softly.assertThat(retried.dropped()).isOne();
            softly.assertThat(exists("forecast_run_p20261112")).isFalse();
        });
    }

    @Test
    void 다른_실행이_같은_파티션을_지우는_중이면_건드리지_않는다() throws Exception {
        // given
        partition("forecast_run", DAY);
        forecastRun(DAY, 1);
        MemoryObjects objects = new MemoryObjects();
        service(objects, false).run();
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        try (Connection other = database.connection(); Statement statement = other.createStatement()) {
            other.setAutoCommit(false);
            statement.execute("SELECT pg_advisory_xact_lock(" + PartitionExportRepository.ADVISORY_NAMESPACE
                + ", hashtext('forecast_run_p20261112'))");

            // when
            service(objects, true, 1000, metrics, source).run();

            other.rollback();
        }

        // then
        assertSoftly(softly -> {
            softly.assertThat(exists("forecast_run_p20261112")).isTrue();
            softly.assertThat(metrics.get("salmonbus_archive_partition_deferrals").tag("reason", "BUSY").counter()
                .count()).isOne();
        });
    }

    @Test
    void 두_실행이_동시에_돌아도_한_번만_내보내고_한_번만_지운다() throws Exception {
        // given
        partition("forecast_run", DAY);
        partition("forecast_record", DAY);
        forecastRun(DAY, 1);
        forecastRecord(DAY, 1, EDGE_DOUBLES);
        jdbc.sql("CREATE TABLE copy_record AS SELECT * FROM forecast_record_p20261112").update();
        MemoryObjects objects = new MemoryObjects();
        List<PartitionExportService> services = List.of(
            service(objects, true, 1000, new SimpleMeterRegistry(),
                new DriverManagerDataSource(database.jdbcUrl(), database.username(), database.password())),
            service(objects, true, 1000, new SimpleMeterRegistry(),
                new DriverManagerDataSource(database.jdbcUrl(), database.username(), database.password())));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<PartitionExportService.RunReport>> runs = new ArrayList<>();

        // when
        try {
            for (PartitionExportService each : services) {
                runs.add(executor.submit(() -> {
                    start.await();
                    return each.run();
                }));
            }
            start.countDown();
            for (Future<PartitionExportService.RunReport> run : runs) {
                run.get(60, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
        service(objects, true).run();

        // then
        assertSoftly(softly -> {
            softly.assertThat(exists("forecast_run_p20261112")).isFalse();
            softly.assertThat(exists("forecast_record_p20261112")).isFalse();
            softly.assertThat(count("SELECT count(*) FROM daily_export")).isEqualTo(2);
            softly.assertThat(count("SELECT count(*) FROM daily_export WHERE dropped_at IS NOT NULL")).isEqualTo(2);
        });
        Map<String, Object> ledger = ledger("forecast_record", DAY).orElseThrow();
        PartitionRestore.restore(jdbc, objects, (String) ledger.get("object_key"),
            ((String) ledger.get("sha256")).trim(), "restored_concurrent");
        assertSameRows("copy_record", "restored_concurrent");
    }

    @Test
    void 관측_테이블이_일반_테이블이면_건너뛰었다고_알리고_예보_기록은_그대로_내보내고_지운다() {
        // given
        String schema = "observation_plain_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.sql("CREATE SCHEMA " + schema).update();
        for (String table : PartitionExportRepository.OBSERVATION_TABLES) {
            jdbc.sql("CREATE TABLE " + schema + "." + table + " (id bigint NOT NULL)").update();
        }
        DriverManagerDataSource plainFirst = new DriverManagerDataSource(
            database.jdbcUrl() + "?currentSchema=" + schema + ",public", database.username(), database.password());
        partition("forecast_run", DAY);
        forecastRun(DAY, 1);
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();

        try {
            // when
            PartitionExportService.RunReport report =
                service(new MemoryObjects(), true, 1000, metrics, plainFirst).run();

            // then
            assertSoftly(softly -> {
                softly.assertThat(report.dropped()).isOne();
                softly.assertThat(jdbc.sql("SELECT table_name FROM daily_export").query(String.class).list())
                    .containsExactly("forecast_run");
                for (String table : PartitionExportRepository.OBSERVATION_TABLES) {
                    softly.assertThat(metrics.get("salmonbus_archive_partition_deferrals").tag("table", table)
                        .tag("reason", "NOT_PARTITIONED").counter().count()).as(table).isOne();
                    softly.assertThat(JdbcClient.create(plainFirst)
                        .sql("SELECT relkind::text FROM pg_class WHERE oid = to_regclass(?)")
                        .param(table).query(String.class).single()).as(table).isEqualTo("r");
                }
            });
        } finally {
            jdbc.sql("DROP SCHEMA " + schema + " CASCADE").update();
        }
    }

    /** S3 대역이다. 조건부 PUT 처럼 같은 키가 이미 있으면 덮어쓰지 않고 그대로 두며, 올린 내용을 다시 읽어 견주지 않는다. */
    static final class MemoryObjects implements ArchiveObjects {
        final Map<String, byte[]> values = new HashMap<>();
        final List<String> puts = new ArrayList<>();
        final List<String> released = new ArrayList<>();
        boolean failManifestOnce;
        boolean failRelease;

        @Override
        public synchronized void putVerified(String key, byte[] bytes) {
            values.putIfAbsent(key, bytes.clone());
            puts.add(key);
            if (failManifestOnce && key.contains("/manifest-")) {
                failManifestOnce = false;
                throw new IllegalStateException("RESPONSE_LOST");
            }
        }

        @Override
        public synchronized byte[] read(String key, final int limit) {
            byte[] bytes = Objects.requireNonNull(values.get(key), key);
            if (bytes.length > limit) {
                throw new IllegalStateException("LIMIT");
            }
            return bytes.clone();
        }

        @Override
        public synchronized void release(String prefix) {
            if (failRelease) {
                throw new IllegalStateException("RELEASE_FAILED");
            }
            released.add(prefix);
        }

        synchronized List<String> manifestRoots() {
            return puts.stream()
                .filter(k -> k.contains("/manifest-"))
                .map(k -> k.substring(PREFIX.length(), k.indexOf("/manifest-")))
                .toList();
        }
    }
}
