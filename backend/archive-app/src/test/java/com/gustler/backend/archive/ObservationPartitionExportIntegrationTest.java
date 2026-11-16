package com.gustler.backend.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.support.TestDatabase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;

/**
 * 관측 두 테이블을 운영 전환 SQL({@code deploy/sql/sal434-04a·04})로 날짜 파티션 테이블로 바꾼 격리 DB 에서, 날짜 파티션과 첫
 * 파티션({@code <table>_initial})의 내보내기·확인·떼어 지우기를 검증한다. 줄이 없는 DB 는 마이그레이션이 두 테이블을 처음부터 파티션
 * 테이블로 만들므로, 그 마이그레이션 앞에서 배치 한 줄을 넣어 두 테이블을 일반 테이블로 남긴 뒤 전환 SQL 을 실행한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ObservationPartitionExportIntegrationTest {
    static final String PREFIX = "salmonbus/test/lake-v3/";
    static final LocalDate DAY = LocalDate.parse("2026-11-12");
    static final List<String> OBSERVATIONS = List.of("observation_batch", "vehicle_observation");
    /** 줄이 없는 관측 테이블을 파티션 테이블로 다시 만드는 마이그레이션 바로 앞 버전이다. */
    static final String BEFORE_PARTITIONING = "83";
    TestDatabase database;
    DriverManagerDataSource source;
    JdbcClient jdbc;
    long version;
    Clock clock;

    @BeforeAll
    void partitionObservationTables() throws Exception {
        database = TestDatabase.create();
        source = new DriverManagerDataSource(database.jdbcUrl(), database.username(), database.password());
        Flyway.configure().dataSource(source).target(BEFORE_PARTITIONING).load().migrate();
        jdbc = JdbcClient.create(source);
        final long route = jdbc.sql("INSERT INTO"
            + " route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name)"
            + " VALUES('test','GBIS','test','3330','A','B') RETURNING id").query(Long.class).single();
        version = jdbc.sql("INSERT INTO route_version(route_id,content_digest,valid_from) VALUES(?,?,'2026-01-01')"
            + " RETURNING id").params(route, "0".repeat(64)).query(Long.class).single();
        jdbc.sql("INSERT INTO route_stop(route_version_id,stop_order,stop_id,name,direction,boarding_allowed)"
            + " VALUES(?,1,'s1','S1','UP',true),(?,2,'s2','S2','UP',true)").params(version, version).update();
        jdbc.sql("""
            INSERT INTO observation_batch(route_version_id, scheduled_at, attempt_number, attempt_key, outcome,
                normalization_version, collection_strategy_version)
            VALUES (?, now(), 1, 'before-partitioning', 'RESERVED', 'n', 'c')
            """).param(version).update();
        Flyway.configure().dataSource(source).load().migrate();
        try (Connection connection = database.connection()) {
            run(connection, "sal434-04a-prepare-observation-partition.sql");
            execute(connection, "SET sal434.count_rows = 'storage'");
            runConfirmed(connection, "sal434-04-partition-observation-tables.sql", "sal434.confirm_partition");
        }
    }

    @AfterAll
    void close() {
        if (database != null) {
            database.close();
        }
    }

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE observation_batch, vehicle_observation, forecast_run, daily_export").update();
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
        for (String table : OBSERVATIONS) {
            jdbc.sql("CREATE TABLE IF NOT EXISTS " + table + "_initial PARTITION OF " + table
                + " FOR VALUES FROM ('2000-01-01') TO ('2000-01-02')").update();
        }
        clock = Clock.fixed(Instant.parse("2026-11-16T03:00:00Z"), ZoneOffset.UTC);
    }

    PartitionExportService service(ArchiveObjects objects, final boolean dropEnabled) {
        return service(objects, dropEnabled, new SimpleMeterRegistry());
    }

    PartitionExportService service(ArchiveObjects objects, final boolean dropEnabled, SimpleMeterRegistry metrics) {
        PartitionExportSettings settings = new PartitionExportSettings(dropEnabled,
            PartitionExportSettings.MIN_RETENTION_DAYS, PREFIX, Duration.ofHours(1), 2, Duration.ofMillis(300));
        PartitionExportRepository repository = new PartitionExportRepository(jdbc, new JdbcTransactionManager(source),
            settings.lockTimeout());
        return new PartitionExportService(repository, objects, clock, metrics, settings);
    }

    void partition(String table, LocalDate date) {
        jdbc.sql("CREATE TABLE " + table + "_p" + date.toString().replace("-", "") + " PARTITION OF " + table
            + " FOR VALUES FROM ('" + date + "') TO ('" + date.plusDays(1) + "')").update();
    }

    /** 배치 한 줄과 그 관측 두 줄을 넣는다. 배치의 시각은 모두 {@code at} 이고 입력 확인 시각만 {@code confirmedAt} 이다. */
    long batch(LocalDate sourceDate, String at, String confirmedAt) {
        final long batch = jdbc.sql("""
            INSERT INTO observation_batch(source_date, route_version_id, scheduled_at, attempt_number, attempt_key,
                requested_at, response_received_at, completed_at, input_confirmed_at, outcome, stored_rows,
                normalization_version, collection_strategy_version)
            VALUES (?, ?, CAST(? AS timestamptz), 1, gen_random_uuid()::text, CAST(? AS timestamptz),
                CAST(? AS timestamptz), CAST(? AS timestamptz), CAST(? AS timestamptz), 'SUCCESS_ROWS', 2, 'n', 'c')
            RETURNING id
            """).params(sourceDate, version, at, at, at, at, confirmedAt).query(Long.class).single();
        for (int row = 1; row <= 2; row++) {
            jdbc.sql("""
                INSERT INTO vehicle_observation(source_date, observation_batch_id, route_version_id, source_row_number,
                    vehicle_id, stop_order, stop_id, running_state, passed_stop_order, remaining_seats)
                VALUES (?, ?, ?, ?, ?, 1, 's1', 2, 1, 12)
                """).params(sourceDate, batch, version, row, "vehicle-" + row).update();
        }
        return batch;
    }

    /** 첫 파티션에 다 지난 줄을, {@link #DAY} 날짜 파티션에 줄을 넣고 둘 다 복사해 둔다. */
    void seedClosedRows() {
        batch(PartitionExportRepository.INITIAL_DATE, "2026-11-09T01:00:00Z", "2026-11-09T01:00:30Z");
        batch(PartitionExportRepository.INITIAL_DATE, "2026-11-10T01:00:00Z", "2026-11-10T01:00:30Z");
        for (String table : OBSERVATIONS) {
            partition(table, DAY);
        }
        batch(DAY, "2026-11-12T01:00:00Z", "2026-11-12T01:00:30Z");
        copy();
    }

    void copy() {
        for (String table : OBSERVATIONS) {
            jdbc.sql("CREATE TABLE copy_" + table + "_initial AS SELECT * FROM " + table + "_initial").update();
            if (exists(table + "_p20261112")) {
                jdbc.sql("CREATE TABLE copy_" + table + "_p20261112 AS SELECT * FROM " + table + "_p20261112")
                    .update();
            }
        }
    }

    long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    boolean exists(String relation) {
        return jdbc.sql("SELECT to_regclass(?) IS NOT NULL").param(relation).query(Boolean.class).single();
    }

    Optional<Map<String, Object>> ledger(String table, LocalDate date) {
        return jdbc.sql("SELECT * FROM daily_export WHERE table_name = ? AND source_date = ?").params(table, date)
            .query().listOfRows().stream().findFirst();
    }

    void assertSameRows(String original, String restored) {
        assertThat(count("SELECT count(*) FROM ((SELECT * FROM " + original + " EXCEPT ALL SELECT * FROM " + restored
            + ") UNION ALL (SELECT * FROM " + restored + " EXCEPT ALL SELECT * FROM " + original + ")) d"))
            .as(original).isZero();
    }

    void restoreAndCompare(
        PartitionExportIntegrationTest.MemoryObjects objects,
        String table,
        LocalDate date,
        String original
    ) {
        Map<String, Object> ledger = ledger(table, date).orElseThrow();
        String target = "restored_" + original;
        PartitionRestore.restore(jdbc, objects, (String) ledger.get("object_key"),
            ((String) ledger.get("sha256")).trim(), target);
        assertSameRows(original, target);
    }

    /** 첫 파티션과 날짜 파티션의 줄이 하나도 빠지지 않고 그대로 남았는지 본다. */
    void assertRowsKept() {
        for (String table : OBSERVATIONS) {
            assertSameRows("copy_" + table + "_initial", table + "_initial");
            if (exists("copy_" + table + "_p20261112")) {
                assertSameRows("copy_" + table + "_p20261112", table + "_p20261112");
            }
        }
        assertThat(count("SELECT count(*) FROM daily_export WHERE dropped_at IS NOT NULL")).isZero();
    }

    @Test
    void 닫힌_날짜_파티션은_파티션_키를_넣은_유일_키_순서로_내보내고_S3_파일만으로_되살린다() {
        // given
        for (String table : OBSERVATIONS) {
            partition(table, DAY);
            partition(table, DAY.plusDays(1));
        }
        batch(DAY, "2026-11-12T01:00:00Z", "2026-11-12T01:00:30Z");
        batch(DAY, "2026-11-12T02:00:00Z", "2026-11-12T02:00:30Z");
        batch(DAY.plusDays(1), "2026-11-13T01:00:00Z", "2026-11-13T01:00:30Z");
        PartitionExportIntegrationTest.MemoryObjects objects = new PartitionExportIntegrationTest.MemoryObjects();

        // when
        service(objects, false).run();

        // then
        Map<String, Object> ledger = ledger("vehicle_observation", DAY).orElseThrow();
        PartitionManifest manifest = PartitionManifest.parse(objects.values.get((String) ledger.get("object_key")));
        assertSoftly(softly -> {
            softly.assertThat(jdbc.sql("SELECT table_name || ' ' || source_date FROM daily_export"
                + " WHERE source_date <> '2000-01-01' ORDER BY 1")
                .query(String.class).list())
                .containsExactly("observation_batch 2026-11-12", "vehicle_observation 2026-11-12");
            softly.assertThat(ledger.get("row_count")).isEqualTo(4L);
            softly.assertThat(ledger.get("verified_at")).isNotNull();
            softly.assertThat((String) ledger.get("object_key"))
                .startsWith(PREFIX + "vehicle_observation/source_date=2026-11-12/manifest-");
            softly.assertThat(manifest.partition()).isEqualTo("vehicle_observation_p20261112");
            softly.assertThat(manifest.orderBy()).containsExactly("id", "source_date");
            softly.assertThat(manifest.columns()).contains(new PartitionManifest.Column("source_date", "date"));
            softly.assertThat(manifest.rows()).isEqualTo(4);
        });
        restoreAndCompare(objects, "observation_batch", DAY, "observation_batch_p20261112");
        restoreAndCompare(objects, "vehicle_observation", DAY, "vehicle_observation_p20261112");
    }

    @Test
    void 기본_파티션에_든_관측_줄은_수동_SQL로_제_날짜_파티션에_옮긴_뒤에야_내보내고_S3_파일만으로_옮기기_전과_같게_되살린다()
        throws Exception {
        // given
        batch(DAY, "2026-11-12T01:00:00Z", "2026-11-12T01:00:30Z");
        batch(DAY, "2026-11-12T02:00:00Z", "2026-11-12T02:00:30Z");
        for (String table : OBSERVATIONS) {
            jdbc.sql("CREATE TABLE copy_" + table + "_default AS SELECT * FROM " + table + "_default").update();
        }
        PartitionExportIntegrationTest.MemoryObjects objects = new PartitionExportIntegrationTest.MemoryObjects();

        // when
        service(objects, true).run();

        // then
        assertSoftly(softly -> {
            softly.assertThat(count("SELECT count(*) FROM daily_export WHERE source_date = '2026-11-12'")).isZero();
            softly.assertThat(count("SELECT count(*) FROM observation_batch_default")).isEqualTo(2);
            softly.assertThat(count("SELECT count(*) FROM vehicle_observation_default")).isEqualTo(4);
        });

        // when
        try (Connection connection = database.connection()) {
            for (String table : OBSERVATIONS) {
                execute(connection, "SET salmonbus.default_rows_table = '" + table + "'");
                run(connection, "move-partition-default-rows.sql");
            }
        }
        service(objects, true).run();

        // then
        assertSoftly(softly -> {
            softly.assertThat(jdbc.sql("SELECT table_name || ' ' || row_count FROM daily_export"
                + " WHERE source_date = '2026-11-12' AND dropped_at IS NOT NULL ORDER BY 1")
                .query(String.class).list()).containsExactly("observation_batch 2", "vehicle_observation 4");
            softly.assertThat(count("SELECT count(*) FROM observation_batch")).isZero();
            softly.assertThat(count("SELECT count(*) FROM vehicle_observation")).isZero();
        });
        for (String table : OBSERVATIONS) {
            restoreAndCompare(objects, table, DAY, "copy_" + table + "_default");
        }
    }

    @Test
    void 관측_테이블과_예보_기록_테이블은_같은_보관_날짜_수로_같은_날짜까지_내보내고_지운다() {
        // given
        LocalDate recent = DAY.plusDays(1);
        for (LocalDate date : List.of(DAY, recent)) {
            partition("forecast_run", date);
            jdbc.sql("""
                INSERT INTO forecast_run (source_date, observation_batch_id, route_id, route_version_id, received_at,
                    confirmed_at, model_deployment_id, outcome, prediction_count, source_incomplete, counted_rows)
                VALUES (?, 1, 1, 2, now(), now(), NULL, 'EMPTY', 0, false, 0)
                """).param(date).update();
            for (String table : OBSERVATIONS) {
                partition(table, date);
            }
        }
        batch(DAY, "2026-11-12T01:00:00Z", "2026-11-12T01:00:30Z");
        batch(recent, "2026-11-13T01:00:00Z", "2026-11-13T01:00:30Z");

        // when
        service(new PartitionExportIntegrationTest.MemoryObjects(), true).run();

        // then
        assertSoftly(softly -> {
            softly.assertThat(jdbc.sql("SELECT table_name || ' ' || source_date FROM daily_export"
                + " WHERE source_date <> '2000-01-01' AND dropped_at IS NOT NULL ORDER BY 1")
                .query(String.class).list()).containsExactly("forecast_run 2026-11-12",
                    "observation_batch 2026-11-12", "vehicle_observation 2026-11-12");
            softly.assertThat(exists("forecast_run_p20261113")).isTrue();
            softly.assertThat(count("SELECT count(*) FROM observation_batch_p20261113")).isOne();
            softly.assertThat(count("SELECT count(*) FROM vehicle_observation_p20261113")).isEqualTo(2);
        });
    }

    @Test
    void 첫_파티션은_든_배치의_가장_늦은_시각이_보관_경계를_지나야_한_덩어리로_내보내고_지운다() {
        // given
        batch(PartitionExportRepository.INITIAL_DATE, "2026-11-09T01:00:00Z", "2026-11-09T01:00:30Z");
        batch(PartitionExportRepository.INITIAL_DATE, "2026-11-10T01:00:00Z", "2026-11-12T15:00:00Z");
        copy();
        PartitionExportIntegrationTest.MemoryObjects objects = new PartitionExportIntegrationTest.MemoryObjects();

        // when
        PartitionExportService.RunReport waiting = service(objects, true).run();

        // then
        assertSoftly(softly -> {
            softly.assertThat(waiting.exported()).isZero();
            softly.assertThat(count("SELECT count(*) FROM daily_export")).isZero();
            softly.assertThat(objects.puts).isEmpty();
        });
        assertRowsKept();

        // when
        clock = Clock.fixed(Instant.parse("2026-11-17T03:00:00Z"), ZoneOffset.UTC);
        PartitionExportService.RunReport report = service(objects, true).run();

        // then
        Map<String, Object> ledger = ledger("observation_batch", PartitionExportRepository.INITIAL_DATE).orElseThrow();
        PartitionManifest manifest = PartitionManifest.parse(objects.values.get((String) ledger.get("object_key")));
        assertSoftly(softly -> {
            softly.assertThat(report.dropped()).isEqualTo(2);
            softly.assertThat(report.oldestPending()).isNull();
            softly.assertThat(exists("observation_batch_initial")).isFalse();
            softly.assertThat(exists("vehicle_observation_initial")).isFalse();
            softly.assertThat(jdbc.sql("SELECT table_name || ' ' || source_date || ' ' || segment || ' ' || row_count"
                + " FROM daily_export WHERE dropped_at IS NOT NULL ORDER BY 1").query(String.class).list())
                .containsExactly("observation_batch 2000-01-01 0 2", "vehicle_observation 2000-01-01 0 4");
            softly.assertThat((String) ledger.get("object_key"))
                .startsWith(PREFIX + "observation_batch/source_date=2000-01-01/manifest-");
            softly.assertThat(manifest.partition()).isEqualTo("observation_batch_initial");
            softly.assertThat(manifest.sourceDate()).isEqualTo("2000-01-01");
            softly.assertThat(objects.manifestRoots()).containsExactly("observation_batch/source_date=2000-01-01",
                "vehicle_observation/source_date=2000-01-01");
        });
        restoreAndCompare(objects, "observation_batch", PartitionExportRepository.INITIAL_DATE,
            "copy_observation_batch_initial");
        restoreAndCompare(objects, "vehicle_observation", PartitionExportRepository.INITIAL_DATE,
            "copy_vehicle_observation_initial");
    }

    @Test
    void 배치_첫_파티션을_먼저_지웠으면_그_장부의_확인으로_관측_첫_파티션을_이어_지운다() {
        // given
        batch(PartitionExportRepository.INITIAL_DATE, "2026-11-10T01:00:00Z", "2026-11-10T01:00:30Z");
        copy();
        PartitionExportIntegrationTest.MemoryObjects objects = new PartitionExportIntegrationTest.MemoryObjects();
        ArchiveObjects failing = failing(objects, key -> key.contains("/vehicle_observation/"));

        // when
        service(failing, true).run();

        // then
        assertSoftly(softly -> {
            softly.assertThat(exists("observation_batch_initial")).isFalse();
            softly.assertThat(ledger("vehicle_observation", PartitionExportRepository.INITIAL_DATE)).isEmpty();
        });
        assertSameRows("copy_vehicle_observation_initial", "vehicle_observation_initial");

        // when
        service(objects, true).run();

        // then
        assertThat(exists("vehicle_observation_initial")).isFalse();
        restoreAndCompare(objects, "vehicle_observation", PartitionExportRepository.INITIAL_DATE,
            "copy_vehicle_observation_initial");
    }

    @Test
    void 파일을_올리지_못하면_첫_파티션도_날짜_파티션도_줄을_지우지_않는다() {
        // given
        seedClosedRows();
        ArchiveObjects failing = failing(new PartitionExportIntegrationTest.MemoryObjects(),
            key -> key.endsWith(".jsonl.gz"));

        // when
        PartitionExportService.RunReport report = service(failing, true).run();

        // then
        assertSoftly(softly -> {
            softly.assertThat(report.dropped()).isZero();
            softly.assertThat(report.pending()).isEqualTo(4);
            softly.assertThat(count("SELECT count(*) FROM daily_export")).isZero();
        });
        assertRowsKept();
    }

    @Test
    void S3에서_다시_읽은_파일이_장부와_다르면_첫_파티션도_날짜_파티션도_줄을_지우지_않는다() {
        // given
        seedClosedRows();
        PartitionExportIntegrationTest.MemoryObjects objects = new PartitionExportIntegrationTest.MemoryObjects();
        service(objects, false).run();
        for (String key : List.copyOf(objects.values.keySet())) {
            if (key.endsWith(".jsonl.gz")) {
                List<String> rows = new ArrayList<>(ArchiveFiles.decode(objects.values.get(key)));
                rows.removeLast();
                rows.add("{\"changed\":true}");
                objects.values.put(key, ArchiveFiles.encode(rows));
            }
        }
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();

        // when
        PartitionExportService.RunReport report = service(objects, true, metrics).run();

        // then
        assertSoftly(softly -> {
            softly.assertThat(report.dropped()).isZero();
            softly.assertThat(metrics.get("salmonbus_archive_partition_failures").tag("reason", "FILE_HASH_MISMATCH")
                .counters()).hasSize(2);
        });
        assertRowsKept();
    }

    @Test
    void 내보내다_끊기거나_확인한_장부의_manifest를_잃으면_줄을_남기고_다음_회차에_확인한_뒤에만_지운다() {
        // given
        seedClosedRows();
        PartitionExportIntegrationTest.MemoryObjects objects = new PartitionExportIntegrationTest.MemoryObjects();
        ArchiveObjects lostOnce = failing(objects, new Predicate<>() {
            private boolean failed;

            @Override
            public boolean test(String key) {
                if (failed || !key.contains("/observation_batch/source_date=2000-01-01/manifest-")) {
                    return false;
                }
                failed = true;
                return true;
            }
        });

        // when
        service(lostOnce, false).run();

        // then
        assertThat(ledger("observation_batch", PartitionExportRepository.INITIAL_DATE)).isEmpty();
        assertRowsKept();

        // when
        String manifest = (String) ledger("observation_batch", DAY).orElseThrow().get("object_key");
        byte[] original = objects.values.remove(manifest);
        service(objects, true).run();

        // then
        assertSoftly(softly -> {
            softly.assertThat(exists("observation_batch_initial")).isFalse();
            softly.assertThat(exists("observation_batch_p20261112")).isTrue();
            softly.assertThat(ledger("observation_batch", DAY).orElseThrow().get("dropped_at")).isNull();
        });
        assertSameRows("copy_observation_batch_p20261112", "observation_batch_p20261112");

        // when
        objects.values.put(manifest, original);
        service(objects, true).run();

        // then
        assertThat(exists("observation_batch_p20261112")).isFalse();
        restoreAndCompare(objects, "observation_batch", PartitionExportRepository.INITIAL_DATE,
            "copy_observation_batch_initial");
        restoreAndCompare(objects, "observation_batch", DAY, "copy_observation_batch_p20261112");
    }

    /** {@code fails} 에 맞는 키는 올리기 전에 실패하는 S3 대역이다. 읽기는 그대로 넘긴다. */
    static ArchiveObjects failing(ArchiveObjects delegate, Predicate<String> fails) {
        return new ArchiveObjects() {
            @Override
            public void putVerified(String key, byte[] bytes) {
                if (fails.test(key)) {
                    throw new IllegalStateException("UPLOAD_FAILED");
                }
                delegate.putVerified(key, bytes);
            }

            @Override
            public byte[] read(String key, final int limit) {
                return delegate.read(key, limit);
            }

            @Override
            public void release(String prefix) {
                delegate.release(prefix);
            }
        };
    }

    static void run(Connection connection, String file) throws IOException, SQLException {
        for (String statement : statements(Files.readString(sqlDirectory().resolve(file)))) {
            execute(connection, statement);
        }
    }

    /**
     * 승인값으로 오늘 한국 날짜를 받는 SQL 파일을 실행한다. 파일의 첫 문장 {@code BEGIN} 을 실행한 뒤 그 트랜잭션의 한국 날짜를
     * 승인값으로 넣는다. 파일이 승인값과 견주는 날짜도 같은 트랜잭션의 {@code now()} 에서 나오므로, 실행하는 사이 한국 날짜가 바뀌어도
     * 둘은 같다.
     *
     * @param confirmation 승인값을 담는 설정 이름
     */
    static void runConfirmed(Connection connection, String file, String confirmation)
        throws IOException, SQLException {
        List<String> script = statements(Files.readString(sqlDirectory().resolve(file)));
        assertThat(script.getFirst()).as(file + " 의 첫 문장").isEqualToIgnoringCase("BEGIN");
        execute(connection, script.getFirst());
        execute(connection, "SELECT set_config('" + confirmation
            + "', to_char((now() AT TIME ZONE 'Asia/Seoul')::date, 'YYYY-MM-DD'), false)");
        for (String statement : script.subList(1, script.size())) {
            execute(connection, statement);
        }
    }

    static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    static Path sqlDirectory() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.isDirectory(directory.resolve("deploy/sql"))) {
            directory = directory.getParent();
        }
        if (directory == null) {
            throw new IllegalStateException("deploy/sql 디렉터리를 찾을 수 없다");
        }
        return directory.resolve("deploy/sql");
    }

    /** psql 처럼 문장을 나눈다. 따옴표, 달러 따옴표, 줄 주석 안의 세미콜론은 문장 끝이 아니다. */
    static List<String> statements(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        Matcher dollar = Pattern.compile("\\$[A-Za-z_]*\\$").matcher(script);
        int index = 0;
        while (index < script.length()) {
            final char c = script.charAt(index);
            if (script.startsWith("--", index)) {
                index = lineEnd(script, index);
                continue;
            }
            if (c == '\'') {
                final int end = closingQuote(script, index);
                current.append(script, index, end + 1);
                index = end + 1;
                continue;
            }
            if (c == '$' && dollar.region(index, script.length()).lookingAt()) {
                String tag = dollar.group();
                final int end = script.indexOf(tag, index + tag.length()) + tag.length();
                current.append(script, index, end);
                index = end;
                continue;
            }
            if (c == ';') {
                addStatement(statements, current);
                index++;
                continue;
            }
            current.append(c);
            index++;
        }
        addStatement(statements, current);
        return statements;
    }

    private static int lineEnd(String script, final int index) {
        final int end = script.indexOf('\n', index);
        if (end < 0) {
            return script.length();
        }
        return end;
    }

    private static int closingQuote(String script, final int start) {
        int end = start + 1;
        while (true) {
            end = script.indexOf('\'', end);
            if (end + 1 < script.length() && script.charAt(end + 1) == '\'') {
                end += 2;
                continue;
            }
            return end;
        }
    }

    private static void addStatement(List<String> statements, StringBuilder current) {
        String statement = current.toString().strip();
        current.setLength(0);
        if (!statement.isEmpty()) {
            statements.add(statement);
        }
    }
}
