package com.gustler.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.gustler.backend.support.TestDatabase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.assertj.core.api.SoftAssertions;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SAL-434 전환 수동 SQL({@code deploy/sql/sal434-*.sql})과, 줄이 없는 DB에서 같은 일을 하는 마이그레이션을 PostgreSQL 18에서
 * 확인한다.
 *
 * <p>마이그레이션을 끝까지 적용한 DB에 운영에만 있는 객체(수동 인덱스, 옛 이관 기록 테이블과 그 FK, 배치의 이관 묶음 FK)를
 * 흉내 내고 이전 배포본이 쓰던 테이블에 줄을 넣은 것을 원본으로 한 번 만든다. 시험마다 그 원본을 복제한 새 DB를 쓴다. SQL 파일은
 * psql처럼 한 연결에서 문장 단위로 실행하고, 실패하면 그 연결의 트랜잭션을 되돌린다.
 */
class TransitionSqlTest {
    private static final List<String> LEGACY_TABLES = List.of("alighting_settlement_pass",
        "alighting_settlement_observation", "alighting_settlement_projection", "alighting_settlement_batch_work",
        "alighting_settlement_generation", "alighting_settlement_queue_scan", "alighting_forecast_evaluation_result",
        "alighting_evaluation_archive", "alighting_forecast_evaluation_work", "forecast_evaluation_result",
        "forecast_evaluation_compact", "forecast_evaluation_work", "archive_legacy_member", "archive_legacy_batch",
        "alighting_history_checkpoint", "seat_forecast", "forecast_publication", "stop_demand_statistics",
        "file_statistics_publication", "demand_statistics_version", "model_training_exclusion",
        "statistics_training_exclusion", "operational_archive_file", "operational_archive_job",
        "operational_archive_boundary");
    private static final List<String> LEGACY_VIEWS = List.of("alighting_evaluation_status",
        "alighting_quality_eligible_metric", "alighting_quality_eligible_evaluation",
        "alighting_forecast_evaluation_metric", "training_eligible_seat_forecast", "one_stop_operational_seat_forecast",
        "forecast_evaluation", "forecast_evaluation_pending", "training_eligible_stop_demand_statistics");
    private static final List<String> KEPT_TABLES = List.of("observation_batch", "vehicle_observation", "route",
        "route_version", "route_stop", "model_deployment", "forecast_record", "stop_pass",
        "historical_import_record", "migration_source_record", "historical_import_batch");
    private static final String LEGACY_TRIGGERS = """
        SELECT count(*) FROM pg_trigger WHERE NOT tgisinternal AND tgname IN ('trg_alighting_evaluation_work',
            'trg_alighting_settlement_batch', 'trg_alighting_settlement_batch_cleanup', 'trg_alighting_batch_insert',
            'trg_alighting_batch_update', 'trg_alighting_batch_delete', 'trg_alighting_observation_insert',
            'trg_alighting_observation_update', 'trg_alighting_observation_delete')
        """;
    private static final String LEGACY_FUNCTIONS = """
        SELECT count(*) FROM pg_proc WHERE pronamespace = 'public'::regnamespace AND proname IN (
            'register_alighting_evaluation_work', 'register_alighting_settlement_batch',
            'cleanup_alighting_settlement_batch', 'track_alighting_batch_changes', 'track_alighting_observation_changes',
            'invalidate_alighting_settlement', 'enqueue_alighting_settlement_batch')
        """;
    private static final String FAST = "SET sal434.observe_seconds = '1'";
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    /** 쓰지 않게 된 객체를 지우는 마이그레이션 바로 앞 버전이다. */
    private static final String BEFORE_RETIREMENT = "82";
    private static final String TABLE_NAMES = """
        SELECT string_agg(tablename, ',' ORDER BY tablename) FROM pg_tables
        WHERE schemaname = 'public' AND tablename <> ALL (?)
        """;
    private static final String VIEW_NAMES =
        "SELECT string_agg(viewname, ',' ORDER BY viewname) FROM pg_views WHERE schemaname = 'public'";
    private static final String TRIGGER_DEFINITIONS = """
        SELECT string_agg(pg_get_triggerdef(oid), E'\\n' ORDER BY pg_get_triggerdef(oid))
        FROM pg_trigger WHERE NOT tgisinternal
        """;
    private static final String FUNCTION_DEFINITIONS = """
        SELECT string_agg(pg_get_functiondef(p.oid), E'\\n' ORDER BY p.oid::regprocedure::text)
        FROM pg_proc p WHERE p.pronamespace = 'public'::regnamespace AND p.prokind = 'f'
            AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid = 'pg_proc'::regclass AND d.objid = p.oid
                AND d.deptype = 'e')
        """;
    /** 틀 DB에만 흉내로 만들어 둔, 마이그레이션에 없는 테이블이다. */
    private static final String[] OPERATIONS_ONLY_TABLES =
        { "historical_import_batch", "historical_import_record", "migration_source_record" };
    /** 마이그레이션에는 없고 운영 DB에 직접 만들어 둔 인덱스다. */
    private static final List<String> OPERATIONS_ONLY_INDEXES = List.of(
        "CREATE INDEX ix_archive_evaluation_arrival_fk ON forecast_evaluation_result(arrival_observation_id)",
        "CREATE INDEX ix_archive_compact_arrival_fk ON forecast_evaluation_compact(arrival_observation_id)",
        "CREATE INDEX ix_archive_forecast_publication_fk ON seat_forecast(publication_id)",
        "CREATE INDEX ix_archive_batch_date"
            + " ON observation_batch(route_version_id,(COALESCE(response_received_at,scheduled_at)),id)",
        "CREATE INDEX ix_archive_alighting_pre_observation_id"
            + " ON alighting_forecast_evaluation_result(pre_observation_id)",
        "CREATE INDEX ix_archive_alighting_post_observation_id"
            + " ON alighting_forecast_evaluation_result(post_observation_id)",
        "CREATE INDEX ix_archive_alighting_closure_observation_id"
            + " ON alighting_forecast_evaluation_result(closure_observation_id)",
        "CREATE INDEX ix_archive_alighting_previous_post_observation_id"
            + " ON alighting_forecast_evaluation_result(previous_post_observation_id)",
        "CREATE INDEX ix_archive_alighting_previous_closure_observation_id"
            + " ON alighting_forecast_evaluation_result(previous_closure_observation_id)");

    private static TestDatabase template;

    private final List<String> notices = new ArrayList<>();
    private String name;
    private String url;
    private Connection connection;

    @BeforeEach
    void 운영과_비슷한_원본을_복제한다() throws Exception {
        TestDatabase source = template();
        name = "c_" + UUID.randomUUID().toString().replace("-", "");
        admin(source, "CREATE DATABASE " + name + " TEMPLATE " + databaseName(source));
        url = source.jdbcUrl().replace(databaseName(source), name);
        connection = DriverManager.getConnection(url, source.username(), source.password());
    }

    @AfterEach
    void 복제한_DB를_지운다() throws Exception {
        connection.close();
        admin(template, "DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
    }

    @AfterAll
    static void 원본을_지운다() {
        if (template != null) {
            template.close();
            template = null;
        }
    }

    @Test
    void 사전_확인은_읽기만_하고_전환의_어느_단계에서도_오류_없이_돈다() throws Exception {
        // given
        String before = catalogFingerprint();

        // when
        run("sal434-01-precheck.sql");

        // then
        assertThat(catalogFingerprint()).isEqualTo(before);

        // when
        retireLegacyTables();
        run("sal434-01-precheck.sql");
        partitionObservations("storage");

        // then
        assertThatCode(() -> run("sal434-01-precheck.sql")).doesNotThrowAnyException();
    }

    @Test
    void 사전_확인은_마이그레이션이_만든_테이블과_날짜_파티션을_마이그레이션에_없는_것으로_보이지_않는다() throws Exception {
        // given
        try (TestDatabase empty = TestDatabase.create()) {
            flyway(empty).load().migrate();

            // when
            List<String> plain = outsideMigrations(connection);
            retireLegacyTables();
            partitionObservations("storage");
            List<String> converted = outsideMigrations(connection);
            List<String> migrated;
            try (Connection target = empty.connection()) {
                migrated = outsideMigrations(target);
            }

            // then
            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(plain).as("줄이 있어 관측 테이블이 일반 테이블인 DB").containsExactly(OPERATIONS_ONLY_TABLES);
            softly.assertThat(converted).as("수동 SQL로 관측 테이블을 날짜 파티션으로 바꾼 DB")
                .containsExactly(OPERATIONS_ONLY_TABLES);
            softly.assertThat(migrated).as("줄이 없어 마이그레이션이 관측 테이블을 날짜 파티션으로 만든 DB").isEmpty();
            softly.assertAll();
        }
    }

    @Test
    void 사전_확인은_옛_테이블마다_장부가_줄_수와_맞는지와_조각_번호가_이어지는지를_보인다() throws Exception {
        // given
        run("sal434-02-drop-legacy-triggers.sql", FAST);
        insertForecast("2");
        writeLedger();

        // when
        Map<String, String> matched = ledgerState("seat_forecast");
        Map<String, String> empty = ledgerState("archive_legacy_member");
        execute("DELETE FROM daily_export WHERE table_name = 'seat_forecast'");
        writeSegment("seat_forecast", 0, 1);
        writeSegment("seat_forecast", 2, 1);
        Map<String, String> gap = ledgerState("seat_forecast");
        execute("DELETE FROM seat_forecast WHERE vehicle_observation_id = 2");
        Map<String, String> removed = ledgerState("seat_forecast");

        // then
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(matched).containsEntry("exact_rows", "2").containsEntry("verified_ledger_rows", "2")
            .containsEntry("segments_contiguous", "t").containsEntry("sal434_03_ledger_check", "ok");
        softly.assertThat(empty).containsEntry("exact_rows", "0").containsEntry("sal434_03_ledger_check", "ok(empty)");
        softly.assertThat(gap).containsEntry("segments", "2").containsEntry("last_segment", "2")
            .containsEntry("segments_contiguous", "f").containsEntry("sal434_03_ledger_check", "segment gap");
        softly.assertThat(removed).containsEntry("exact_rows", "1").containsEntry("verified_ledger_rows", "2")
            .containsEntry("sal434_03_ledger_check", "mismatch");
        softly.assertAll();
    }

    @Test
    void 사전_확인은_정답을_마지막으로_계산한_시각을_보인다() throws Exception {
        // given
        execute("UPDATE pass_label SET computed_at = timestamptz '2026-11-15T03:04:05Z'");

        // when
        String computed = evidence("new_pass_label_computed");
        execute("DELETE FROM pass_label");
        String none = evidence("new_pass_label_computed");

        // then
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(OffsetDateTime.parse(computed).toInstant()).isEqualTo(Instant.parse("2026-11-15T03:04:05Z"));
        softly.assertThat(none).isNull();
        softly.assertAll();
    }

    @Test
    void 사전_확인은_관측_테이블의_전환_준비와_바꾼_뒤의_첫_파티션을_보인다() throws Exception {
        // given
        retireLegacyTables();
        run("drop-historical-import-batch-fks.sql");
        run("sal434-04a-prepare-observation-partition.sql");

        // when
        List<String> prepared = observationTables();
        List<Map<String, String>> indexes = precheck(connection, "prebuilt_index");
        runPartitioning("SET sal434.count_rows = 'storage'");
        List<String> converted = observationTables();

        // then
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(prepared).containsExactly("observation_batch:r:t:t", "vehicle_observation:r:t:t");
        softly.assertThat(indexes).hasSize(5).allSatisfy(index ->
            assertThat(index).containsEntry("valid", "t").containsEntry("ready", "t"));
        softly.assertThat(converted).containsExactly("observation_batch:p:t:null", "observation_batch_initial:r:t:t",
            "vehicle_observation:p:t:null", "vehicle_observation_initial:r:t:t");
        softly.assertAll();
    }

    @Test
    void 옛_정산_트리거를_지운_뒤에도_관측과_예측을_저장하고_대기_줄은_더_생기지_않는다() throws Exception {
        // when
        run("sal434-02-drop-legacy-triggers.sql", FAST);

        // then
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(number(LEGACY_TRIGGERS)).isZero();
        softly.assertThat(number(LEGACY_FUNCTIONS)).isZero();
        softly.assertAll();
        final long work = number("SELECT count(*) FROM alighting_forecast_evaluation_work");
        execute("""
            INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,outcome,
                normalization_version,collection_strategy_version,ingestion_origin,response_received_at,
                input_confirmed_at,stored_rows)
            VALUES(1,now(),1,'after-02','SUCCESS_ROWS','n1','c1','LIVE',now(),now(),1);
            INSERT INTO vehicle_observation(observation_batch_id,route_version_id,source_row_number,vehicle_id,
                stop_order,stop_id,running_state,remaining_seats,passed_stop_order)
            SELECT id,1,0,'bus9',1,'st1',1,5,0 FROM observation_batch WHERE attempt_key='after-02';
            """);
        insertForecast("(SELECT max(id) FROM vehicle_observation)");
        assertThat(number("SELECT count(*) FROM seat_forecast")).isEqualTo(2);
        assertThat(number("SELECT count(*) FROM alighting_forecast_evaluation_work")).isEqualTo(work);

        // when
        run("sal434-02-drop-legacy-triggers.sql", FAST);

        // then
        assertThat(number(LEGACY_TRIGGERS)).isZero();
    }

    @Test
    void 옛_정산이_방금_근거를_저장했으면_트리거를_지우지_않는다() throws Exception {
        // given
        execute("""
            INSERT INTO alighting_settlement_projection(route_id,source_date,layout_hash,layout,data_revision,
                observation_limit,cursor_at,coverage_at,state)
            VALUES(1,'2026-11-01',repeat('a',64),'{}',1,1000,now(),now(),decode('00','hex'))
            """);

        // when
        Throwable actual = catchThrowable(() -> run("sal434-02-drop-legacy-triggers.sql", FAST));

        // then
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(actual).hasMessageContaining("옛 정산이").hasMessageContaining("이전 배포본으로 되돌린 worker");
        softly.assertThat(number(LEGACY_TRIGGERS)).isEqualTo(9);
        softly.assertThat(number(LEGACY_FUNCTIONS)).isEqualTo(7);
        softly.assertAll();
    }

    @Test
    void 옛_예보가_아직_발행하거나_닫힌_통과가_쌓이지_않으면_트리거를_지우지_않는다() throws Exception {
        // given
        execute("UPDATE forecast_publication SET published_at = now()");

        // when
        Throwable published = catchThrowable(() -> run("sal434-02-drop-legacy-triggers.sql", FAST));

        // then
        assertThat(published).hasMessageContaining("옛 예보가").hasMessageContaining("SAL-434 배포본이 worker에");

        // given
        execute("UPDATE forecast_publication SET published_at = now() - interval '2 hours'; DELETE FROM stop_pass");

        // when
        Throwable notClosing = catchThrowable(() -> run("sal434-02-drop-legacy-triggers.sql", FAST));

        // then
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(notClosing).hasMessageContaining("닫힌 통과(stop_pass)가 24시간 안에 없다")
            .hasMessageContaining("예보가 돌고 있는지");
        softly.assertThat(number(LEGACY_TRIGGERS)).isEqualTo(9);
        softly.assertAll();
    }

    @Test
    void 통과는_쌓였는데_정답이_쌓이지_않았으면_트리거를_지우지_않는다() throws Exception {
        // given
        execute("UPDATE pass_label SET computed_at = now() - interval '25 hours'");

        // when
        Throwable stale = catchThrowable(() -> run("sal434-02-drop-legacy-triggers.sql", FAST));

        // then
        assertThat(stale).hasMessageContaining("정답(pass_label)").hasMessageContaining("채점 지표");

        // given
        execute("DELETE FROM pass_label");

        // when
        Throwable none = catchThrowable(() -> run("sal434-02-drop-legacy-triggers.sql", FAST));

        // then
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(none).hasMessageContaining("정답(pass_label)").hasMessageContaining("채점 지표");
        softly.assertThat(number(LEGACY_TRIGGERS)).isEqualTo(9);
        softly.assertThat(number(LEGACY_FUNCTIONS)).isEqualTo(7);
        softly.assertAll();
    }

    @Test
    void 승인값이_없거나_옛_정산_트리거가_남아_있으면_옛_테이블을_지우지_않는다() throws Exception {
        // when
        Throwable withTriggers = catchThrowable(() -> runTableDrop(FAST));

        // then
        assertThat(withTriggers).hasMessageContaining("sal434-02 먼저");

        // given
        run("sal434-02-drop-legacy-triggers.sql", FAST);
        writeLedger();

        // when
        Throwable unconfirmed = catchThrowable(() ->
            run("sal434-03-drop-legacy-tables.sql", FAST, "RESET sal434.confirm_drop"));

        // then
        assertThat(unconfirmed).hasMessageContaining("승인값");
        assertLegacyObjectsPresent();

        // when
        Throwable wrongDate = catchThrowable(() ->
            run("sal434-03-drop-legacy-tables.sql", FAST, "SET sal434.confirm_drop = '2000-01-01'"));

        // then
        assertThat(wrongDate).hasMessageContaining("승인값");
        assertLegacyObjectsPresent();
    }

    @Test
    void 이전_배포본의_이관_앱이_최근에_작업했으면_옛_테이블을_지우지_않는다() throws Exception {
        // given
        run("sal434-02-drop-legacy-triggers.sql", FAST);
        execute("""
            INSERT INTO operational_archive_job(id,route_version_id,source_date,cutoff,state,lease_token,lease_until,
                created_at,last_attempt_at)
            VALUES('00000000-0000-0000-0000-000000000009',1,'2026-11-01',now(),'EXPORTING',
                '00000000-0000-0000-0000-000000000010',now(),now(),now())
            """);
        writeLedger();

        // when
        Throwable actual = catchThrowable(() -> runTableDrop(FAST));

        // then
        assertThat(actual).hasMessageContaining("이전 배포본의 이관 앱");
        assertLegacyObjectsPresent();
    }

    @Test
    void 옛_테이블을_내보낸_뒤에_이전_배포본이_썼으면_한_시간이_지났어도_지우지_않는다() throws Exception {
        // given
        run("sal434-02-drop-legacy-triggers.sql", FAST);
        writeArchiveJobTrace("now() - interval '75 minutes'");
        writeLedger();
        execute("UPDATE daily_export SET exported_at = now() - interval '90 minutes'");

        // when
        Throwable actual = catchThrowable(() -> runTableDrop(FAST));

        // then
        assertThat(actual).hasMessageContaining("첫 조각").hasMessageContaining("처음부터 다시 내보낸다");
        assertLegacyObjectsPresent();
    }

    @Test
    void 이전_배포본의_마지막_흔적이_옛_테이블을_내보내기_전의_것이면_지운다() throws Exception {
        // given
        run("sal434-02-drop-legacy-triggers.sql", FAST);
        writeArchiveJobTrace("now() - interval '4 hours'");
        writeLedger();
        execute("UPDATE daily_export SET exported_at = now() - interval '90 minutes'");

        // when
        runTableDrop(FAST);

        // then
        assertThat(scalar("SELECT to_regclass('public.seat_forecast')::text")).isNull();
    }

    @Test
    void 관찰하는_동안_누가_옛_테이블을_읽으면_지우지_않는다() throws Exception {
        // given
        run("sal434-02-drop-legacy-triggers.sql", FAST);
        writeLedger();
        AtomicBoolean reading = new AtomicBoolean(true);
        Thread reader = new Thread(() -> {
            try (Connection other = DriverManager.getConnection(url, template.username(), template.password());
                Statement statement = other.createStatement()) {
                while (reading.get()) {
                    statement.execute("SELECT count(*) FROM seat_forecast");
                    Thread.sleep(100);
                }
            } catch (SQLException | InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        reader.start();

        // when
        Throwable actual;
        try {
            actual = catchThrowable(() -> runTableDrop("SET sal434.observe_seconds = '4'"));
        } finally {
            reading.set(false);
            reader.join();
        }

        // then
        assertThat(actual).hasMessageContaining("읽거나 쓴 곳").hasMessageContaining("seat_forecast");
        assertLegacyObjectsPresent();
    }

    @Test
    void 삭제_대상이_아닌_객체가_옛_테이블에_기대면_목록을_보이고_멈춘다() throws Exception {
        // given
        run("sal434-02-drop-legacy-triggers.sql", FAST);
        writeLedger();
        execute("""
            CREATE TABLE manual_forecast_note (vehicle_observation_id bigint, target_stop_order integer,
                CONSTRAINT fk_manual_note_forecast FOREIGN KEY (vehicle_observation_id, target_stop_order)
                    REFERENCES seat_forecast(vehicle_observation_id, target_stop_order))
            """);

        // when
        Throwable foreignKey = catchThrowable(() -> runTableDrop(FAST));

        // then
        assertThat(foreignKey).hasMessageContaining("manual_forecast_note.fk_manual_note_forecast");
        assertLegacyObjectsPresent();

        // given
        execute("""
            DROP TABLE manual_forecast_note;
            CREATE VIEW active_stop_demand_seed_hourly_total AS SELECT route_version_id, count(*) AS cells
                FROM stop_demand_statistics GROUP BY route_version_id
            """);

        // when
        Throwable view = catchThrowable(() -> runTableDrop(FAST));

        // then
        assertThat(view).hasMessageContaining("active_stop_demand_seed_hourly_total");
        assertLegacyObjectsPresent();
    }

    @Test
    void S3에_검증된_내보내기가_줄_수와_맞지_않으면_지우지_않는다() throws Exception {
        // given
        run("sal434-02-drop-legacy-triggers.sql", FAST);
        writeLedger();
        execute("UPDATE daily_export SET verified_at = NULL WHERE table_name = 'seat_forecast'");

        // when
        Throwable unverified = catchThrowable(() -> runTableDrop(FAST));

        // then
        assertThat(unverified).hasMessageContaining("seat_forecast(줄 1, 장부 0)");
        assertLegacyObjectsPresent();
    }

    @Test
    void 내보낸_뒤_옛_테이블의_줄이_지워지면_조각_합이_줄_수와_달라_지우지_않는다() throws Exception {
        // given
        run("sal434-02-drop-legacy-triggers.sql", FAST);
        insertForecast("2");
        writeLedger();
        execute("DELETE FROM daily_export WHERE table_name = 'seat_forecast'");
        writeSegment("seat_forecast", 0, 1);
        writeSegment("seat_forecast", 1, 1);
        execute("DELETE FROM seat_forecast WHERE vehicle_observation_id = 2");

        // when
        Throwable removed = catchThrowable(() -> runTableDrop(FAST));

        // then
        assertThat(removed).hasMessageContaining("seat_forecast(줄 1, 장부 2)");
        assertLegacyObjectsPresent();
    }

    @Test
    void 옛_테이블_조각_번호에_빈틈이_있으면_합이_맞아도_지우지_않는다() throws Exception {
        // given
        run("sal434-02-drop-legacy-triggers.sql", FAST);
        insertForecast("2");
        writeLedger();
        execute("DELETE FROM daily_export WHERE table_name = 'seat_forecast'");
        writeSegment("seat_forecast", 0, 1);
        writeSegment("seat_forecast", 2, 1);

        // when
        Throwable gap = catchThrowable(() -> runTableDrop(FAST));

        // then
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(gap).hasMessageContaining("seat_forecast(조각 2개인데 마지막 번호 2)");
        softly.assertThat(number("SELECT count(*) FROM seat_forecast")).isEqualTo(2);
        softly.assertAll();
    }

    @Test
    void 옛_테이블과_뷰만_지우고_관측과_다른_영역의_줄은_그대로_두며_두_번_실행해도_된다() throws Exception {
        // given
        String kept = keptRows();
        run("sal434-02-drop-legacy-triggers.sql", FAST);
        writeLedger();

        // when
        runTableDrop(FAST);

        // then
        SoftAssertions softly = new SoftAssertions();
        for (String relation : concat(LEGACY_TABLES, LEGACY_VIEWS)) {
            softly.assertThat(scalar("SELECT to_regclass('public." + relation + "')::text")).as(relation).isNull();
        }
        softly.assertThat(keptRows()).isEqualTo(kept);
        softly.assertThat(number("SELECT count(*) FROM operational_observation")).isEqualTo(6);
        softly.assertThat(number("SELECT count(*) FROM daily_export WHERE dropped_at IS NOT NULL")).isPositive();
        softly.assertThat(notices).anySatisfy(notice -> assertThat(notice).contains("seat_forecast 줄 1 크기"));
        softly.assertAll();

        // when
        runTableDrop(FAST);

        // then
        assertThat(keptRows()).isEqualTo(kept);
    }

    @Test
    void 줄이_있는_DB에서는_마이그레이션이_쓰지_않는_테이블과_뷰와_트리거와_함수를_그대로_둔다() throws Exception {
        // then
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(number("SELECT count(*) FROM flyway_schema_history WHERE success"
            + " AND script LIKE '%drop_retired_forecast_objects%'")).isOne();
        softly.assertThat(number(LEGACY_TRIGGERS)).isEqualTo(9);
        softly.assertThat(number(LEGACY_FUNCTIONS)).isEqualTo(7);
        softly.assertAll();
        assertLegacyObjectsPresent();
    }

    @Test
    void 줄이_없는_DB에서는_마이그레이션이_쓰지_않는_테이블과_뷰와_트리거와_함수를_모두_지운다() throws Exception {
        // given
        try (TestDatabase empty = TestDatabase.create()) {
            // when
            flyway(empty).load().migrate();

            // then
            try (Connection target = empty.connection()) {
                SoftAssertions softly = new SoftAssertions();
                for (String relation : concat(LEGACY_TABLES, LEGACY_VIEWS)) {
                    softly.assertThat(scalarOn(target, "SELECT to_regclass('public." + relation + "')::text"))
                        .as(relation).isNull();
                }
                softly.assertThat(scalarOn(target, LEGACY_TRIGGERS)).isEqualTo("0");
                softly.assertThat(scalarOn(target, LEGACY_FUNCTIONS)).isEqualTo("0");
                softly.assertThat(scalarOn(target, VIEW_NAMES)).isEqualTo("forecast_outcome,operational_observation");
                softly.assertThat(scalarOn(target, "SELECT count(*) FROM forecast_run")).isEqualTo("0");
                softly.assertThat(scalarOn(target, "SELECT count(*) FROM vehicle_observation")).isEqualTo("0");
                softly.assertAll();
            }
        }
    }

    @Test
    void 쓰지_않는_테이블_하나에_줄이_하나만_있어도_아무것도_지우지_않는다() throws Exception {
        // given
        try (TestDatabase single = TestDatabase.create()) {
            flyway(single).target(BEFORE_RETIREMENT).load().migrate();
            String before;
            try (Connection target = single.connection(); Statement statement = target.createStatement()) {
                statement.execute("""
                    INSERT INTO model_training_exclusion(release_id, bundle_digest, calculation_version,
                        statistics_baseline_count, statistics_baseline_not_after, classification, decision_reference,
                        recorded_at)
                    VALUES ('r', repeat('a', 64), 'c', 0, now(), 'OPERATOR_EXCLUDED', 'd', now())
                    """);
                before = schemaShape(target);
            }

            // when
            flyway(single).load().migrate();

            // then
            try (Connection target = single.connection()) {
                assertThat(schemaShape(target)).isEqualTo(before);
            }
        }
    }

    @Test
    void 수동_SQL로_전환을_마친_DB와_줄이_없어_마이그레이션이_바꾼_DB는_테이블과_뷰와_트리거와_함수가_같다() throws Exception {
        // given
        try (TestDatabase empty = TestDatabase.create(); Connection target = empty.connection()) {
            LocalDate beforeMigration = koreanDate(target);
            flyway(empty).load().migrate();
            LocalDate afterMigration = koreanDate(target);

            // when
            retireLegacyTables();
            LocalDate convertedOn = partitionObservations("storage");
            dropInitialPartitions();

            // then
            LocalDate migratedOn = firstPartitionDate(target);
            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(relativeToFirstPartition(schemaShape(connection), convertedOn))
                .isEqualTo(relativeToFirstPartition(schemaShape(target), migratedOn));
            softly.assertThat(migratedOn).as("마이그레이션이 만든 첫 날짜 파티션").isBetween(beforeMigration, afterMigration);
            softly.assertAll();
        }
    }

    @Test
    void 옛_테이블을_지운_뒤에도_Flyway의_검증과_마이그레이션이_통과한다() throws Exception {
        // given
        retireLegacyTables();
        Flyway flyway = Flyway.configure().dataSource(url, template.username(), template.password()).load();

        // when
        flyway.validate();
        MigrateResult result = flyway.migrate();

        // then
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(result.migrationsExecuted).isZero();
        softly.assertThat(scalar("SELECT to_regclass('public.seat_forecast')::text")).isNull();
        softly.assertAll();
    }

    @Test
    void 관측_테이블을_가리키는_FK가_남아_있으면_파티션으로_바꾸지_않는다() throws Exception {
        // given
        retireLegacyTables();
        run("sal434-04a-prepare-observation-partition.sql");

        // when
        Throwable actual = catchThrowable(() -> runPartitioning("SET sal434.count_rows = 'exact'"));

        // then
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(actual).hasMessageContaining("historical_import_record.fk_historical_import_record_target");
        softly.assertThat(scalar("SELECT relkind::text FROM pg_class WHERE oid = 'observation_batch'::regclass"))
            .isEqualTo("r");
        softly.assertThat(scalar("SELECT relkind::text FROM pg_class WHERE oid = 'vehicle_observation'::regclass"))
            .isEqualTo("r");
        softly.assertAll();
    }

    @Test
    void 줄_수를_세는_방식을_고르지_않으면_파티션으로_바꾸지_않는다() throws Exception {
        // given
        retireLegacyTables();
        run("drop-historical-import-batch-fks.sql");
        run("sal434-04a-prepare-observation-partition.sql");

        // when
        Throwable actual = catchThrowable(() -> runPartitioning());

        // then
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(actual).hasMessageContaining("sal434.count_rows");
        softly.assertThat(scalar("SELECT relkind::text FROM pg_class WHERE oid = 'observation_batch'::regclass"))
            .isEqualTo("r");
        softly.assertAll();
    }

    @Test
    void 관측_두_테이블을_날짜_파티션으로_바꿔도_줄과_값이_같고_새_줄은_그날_파티션으로_간다() throws Exception {
        // given
        retireLegacyTables();
        String batches = rows("observation_batch");
        String observations = rows("vehicle_observation");
        final long maxObservation = number("SELECT max(id) FROM vehicle_observation");

        // when
        partitionObservations("exact");

        // then
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(rows("observation_batch")).isEqualTo(batches);
        softly.assertThat(rows("vehicle_observation")).isEqualTo(observations);
        softly.assertThat(notices)
            .anySatisfy(notice -> assertThat(notice).contains("vehicle_observation 줄 수 전후 같음 6"));
        softly.assertThat(scalar("SELECT relkind::text FROM pg_class WHERE oid = 'vehicle_observation'::regclass"))
            .isEqualTo("p");
        softly.assertThat(scalar("SELECT pg_get_expr(relpartbound, oid) FROM pg_class "
            + "WHERE oid = 'vehicle_observation_initial'::regclass"))
            .isEqualTo("FOR VALUES FROM ('2000-01-01') TO ('2000-01-02')");
        softly.assertAll();
        // 새 줄을 한 트랜잭션에서 넣는다. 그 안에서는 now()가 변하지 않아 줄마다 기본값으로 받는 source_date가 같은 날짜다.
        execute("BEGIN");
        LocalDate insertedOn = koreanDate(connection);
        String partitionOfDay = "_p" + insertedOn.format(DateTimeFormatter.BASIC_ISO_DATE);
        execute("""
            INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,outcome,
                normalization_version,collection_strategy_version)
            VALUES(1,now(),1,'after-04','RESERVED','n1','c1')
            """);
        assertThat(scalar("SELECT tableoid::regclass::text || ' ' || source_date FROM observation_batch"
            + " WHERE attempt_key = 'after-04'"))
            .isEqualTo("observation_batch" + partitionOfDay + " " + insertedOn);
        execute("""
            INSERT INTO vehicle_observation(observation_batch_id,route_version_id,source_row_number,vehicle_id,
                stop_order,stop_id,running_state,remaining_seats,passed_stop_order)
            SELECT id,1,0,'bus1',1,'st1',1,5,0 FROM observation_batch WHERE attempt_key = 'after-04'
            """);
        assertThat(scalar("SELECT tableoid::regclass::text || ' ' || source_date || ':' || (id > " + maxObservation
            + ") FROM vehicle_observation WHERE vehicle_id = 'bus1' AND source_date <> '2000-01-01'"))
            .isEqualTo("vehicle_observation" + partitionOfDay + " " + insertedOn + ":true");
        assertThat(number("SELECT count(*) FROM operational_observation")).isEqualTo(7);
        assertThat(failureInTransaction("""
            INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,outcome,
                normalization_version,collection_strategy_version)
            VALUES(1,now(),1,'after-04','RESERVED','n1','c1')
            """)).hasMessageContaining("duplicate key");
        assertThat(failureInTransaction("""
            INSERT INTO vehicle_observation(observation_batch_id,route_version_id,source_row_number,vehicle_id,
                stop_order,stop_id,running_state,remaining_seats,passed_stop_order)
            SELECT id,1,0,'bus2',1,'st1',1,5,0 FROM observation_batch WHERE attempt_key = 'after-04'
            """)).hasMessageContaining("duplicate key");
        assertThat(failureInTransaction("""
            INSERT INTO vehicle_observation(observation_batch_id,route_version_id,source_row_number,vehicle_id,
                stop_order,stop_id,running_state,remaining_seats,passed_stop_order)
            SELECT id,1,1,'bus1',1,'st1',1,5,0 FROM observation_batch WHERE attempt_key = 'after-04'
            """)).hasMessageContaining("duplicate key");
        // 유일성은 날짜 파티션 안에서만 지킨다. 옛 줄과 같은 시도 키도 다른 날짜면 들어간다(파일 머리 주석의 뜻 변화).
        execute("""
            INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,outcome,
                normalization_version,collection_strategy_version)
            VALUES(1,now(),1,'k1','RESERVED','n1','c1')
            """);
        execute("COMMIT");
        assertThat(number("SELECT count(*) FROM observation_batch WHERE attempt_key = 'k1'")).isEqualTo(2);
        Flyway flyway = Flyway.configure().dataSource(url, template.username(), template.password()).load();
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();

        // when
        runPartitioning("SET sal434.count_rows = 'exact'");

        // then
        assertThat(notices).anySatisfy(notice -> assertThat(notice).contains("이미 파티션 테이블이다"));
    }

    @Test
    void 파티션을_되돌리면_새로_들어온_줄을_옛_테이블로_옮기고_원래_모양으로_돌아간다() throws Exception {
        // given
        retireLegacyTables();
        String batches = rows("observation_batch");
        partitionObservations("storage");
        execute("""
            INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,outcome,
                normalization_version,collection_strategy_version)
            VALUES(1,now(),1,'between','RESERVED','n1','c1');
            INSERT INTO vehicle_observation(observation_batch_id,route_version_id,source_row_number,vehicle_id,
                stop_order,stop_id,running_state,remaining_seats,passed_stop_order)
            SELECT id,1,0,'bus7',1,'st1',1,5,0 FROM observation_batch WHERE attempt_key = 'between';
            """);
        final long newBatch = number("SELECT id FROM observation_batch WHERE attempt_key = 'between'");

        // when
        Throwable unconfirmed = catchThrowable(() -> run("sal434-04-rollback-unpartition-observation-tables.sql"));
        runConfirmed("sal434-04-rollback-unpartition-observation-tables.sql", "sal434.confirm_partition_rollback");

        // then
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(unconfirmed).hasMessageContaining("승인값");
        softly.assertThat(scalar("SELECT relkind::text FROM pg_class WHERE oid = 'observation_batch'::regclass"))
            .isEqualTo("r");
        softly.assertThat(scalar("SELECT relkind::text FROM pg_class WHERE oid = 'vehicle_observation'::regclass"))
            .isEqualTo("r");
        softly.assertThat(scalar("SELECT to_regclass('public.observation_batch_initial')::text")).isNull();
        softly.assertThat(number("SELECT count(*) FROM observation_batch WHERE attempt_key = 'between'")).isOne();
        softly.assertThat(number("SELECT count(*) FROM vehicle_observation WHERE vehicle_id = 'bus7'")).isOne();
        softly.assertThat(scalar("SELECT convalidated::text FROM pg_constraint WHERE conname = 'fk_observation_batch'"))
            .isEqualTo("true");
        softly.assertThat(scalar("SELECT attidentity::text FROM pg_attribute "
            + "WHERE attrelid = 'observation_batch'::regclass AND attname = 'id'"))
            .isEqualTo("a");
        softly.assertThat(number("SELECT count(*) FROM operational_observation")).isEqualTo(7);
        softly.assertAll();
        execute("""
            INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,outcome,
                normalization_version,collection_strategy_version)
            VALUES(1,now(),1,'after-rollback','RESERVED','n1','c1')
            """);
        assertThat(number("SELECT id FROM observation_batch WHERE attempt_key = 'after-rollback'"))
            .isGreaterThan(newBatch);
        assertThat(rows("observation_batch WHERE attempt_key NOT IN ('between','after-rollback')")).isEqualTo(batches);

        // when
        runPartitioning("SET sal434.count_rows = 'exact'");

        // then
        SoftAssertions afterRepartition = new SoftAssertions();
        afterRepartition.assertThat(
                scalar("SELECT relkind::text FROM pg_class WHERE oid = 'observation_batch'::regclass"))
            .isEqualTo("p");
        afterRepartition.assertThat(
                number("SELECT count(*) FROM observation_batch WHERE attempt_key IN ('between','after-rollback')"))
            .isEqualTo(2);
        afterRepartition.assertAll();
    }

    private void retireLegacyTables() throws Exception {
        run("sal434-02-drop-legacy-triggers.sql", FAST);
        writeLedger();
        runTableDrop(FAST);
    }

    /** 관측 두 테이블을 날짜 파티션으로 바꾸고, 날짜 파티션을 만들 때 기준으로 삼은 한국 날짜를 돌려준다. */
    private LocalDate partitionObservations(String countMode) throws Exception {
        run("drop-historical-import-batch-fks.sql");
        run("sal434-04a-prepare-observation-partition.sql");
        return runPartitioning("SET sal434.count_rows = '" + countMode + "'");
    }

    /** 이관 앱이 첫 파티션을 내보내 확인한 뒤 하는 것처럼 떼어 지운다. */
    private void dropInitialPartitions() throws SQLException {
        for (String table : List.of("vehicle_observation", "observation_batch")) {
            execute("ALTER TABLE " + table + " DETACH PARTITION " + table + "_initial");
            execute("DROP TABLE " + table + "_initial");
        }
    }

    /** sal434-03의 (라) 계약대로, 줄이 있는 옛 테이블마다 옛 테이블 내보내기의 검증된 조각 0 하나를 장부에 남긴다. */
    private void writeLedger() throws SQLException {
        execute("DELETE FROM daily_export");
        for (String table : LEGACY_TABLES) {
            final long rows = number("SELECT count(*) FROM " + table);
            if (rows > 0) {
                writeSegment(table, 0, rows);
            }
        }
    }

    /** 이전 배포본의 이관 앱이 그 시각에 작업한 흔적 한 줄이다. */
    private void writeArchiveJobTrace(String at) throws SQLException {
        execute("INSERT INTO operational_archive_job(id,route_version_id,source_date,cutoff,state,lease_token,"
            + "lease_until,created_at,last_attempt_at) VALUES('00000000-0000-0000-0000-000000000011',1,"
            + "'2026-11-01'," + at + ",'EXPORTING','00000000-0000-0000-0000-000000000012'," + at + "," + at + ","
            + at + ")");
    }

    /** 옛 테이블 내보내기가 남기는 모양의 검증된 조각 한 줄이다. 키 범위 값은 장부 대조에 쓰이지 않아 흉내만 낸다. */
    private void writeSegment(String table, final int segment, final long rows) throws SQLException {
        execute("INSERT INTO daily_export(table_name, source_date, segment, object_key, row_count, sha256, exported_at,"
            + " verified_at, key_from, key_to) VALUES ('" + table + "', DATE '2000-01-01', " + segment + ", 'legacy/"
            + table + "/segment=" + segment + "/manifest.json', " + rows + ", repeat('a', 64), now(), now(), '["
            + segment + "]', '[" + segment + "]')");
    }

    private static List<String> outsideMigrations(Connection target) throws IOException, SQLException {
        return precheck(target, "not_in_migrations").stream().map(row -> row.get("not_in_migrations")).toList();
    }

    private String evidence(String name) throws IOException, SQLException {
        return precheck(connection, "evidence").stream()
            .filter(row -> name.equals(row.get("evidence")))
            .findFirst()
            .orElseThrow()
            .get("latest");
    }

    private Map<String, String> ledgerState(String table) throws IOException, SQLException {
        return precheck(connection, "sal434_03_ledger_check").stream()
            .filter(row -> table.equals(row.get("name")))
            .findFirst()
            .orElseThrow();
    }

    /** 사전 확인이 보인 관측 테이블마다 이름, 종류, source_date 열 유무, 첫 파티션 날짜 제약의 확인 여부를 잇는다. */
    private List<String> observationTables() throws IOException, SQLException {
        return precheck(connection, "initial_date_check_validated").stream()
            .map(row -> String.join(":", row.get("relname"), row.get("relkind"), row.get("has_source_date"),
                String.valueOf(row.get("initial_date_check_validated"))))
            .toList();
    }

    /** 사전 확인을 실행하고, 열 이름에 {@code column}이 있는 결과 표 하나의 줄을 열 이름과 값으로 돌려준다. */
    private static List<Map<String, String>> precheck(Connection target, String column)
        throws IOException, SQLException {
        List<List<Map<String, String>>> matched = new ArrayList<>();
        try (Statement statement = target.createStatement()) {
            for (String sql : statements(Files.readString(backend().resolve("deploy/sql/sal434-01-precheck.sql")))) {
                if (!statement.execute(sql)) {
                    continue;
                }
                try (ResultSet result = statement.getResultSet()) {
                    if (labels(result).contains(column)) {
                        matched.add(rowsOf(result));
                    }
                }
            }
        } catch (SQLException failure) {
            try (Statement statement = target.createStatement()) {
                statement.execute("ROLLBACK");
            }
            throw failure;
        }
        assertThat(matched).as("사전 확인에서 " + column + " 열이 있는 결과 표").hasSize(1);
        return matched.getFirst();
    }

    private static List<String> labels(ResultSet result) throws SQLException {
        ResultSetMetaData metadata = result.getMetaData();
        List<String> labels = new ArrayList<>();
        for (int index = 1; index <= metadata.getColumnCount(); index++) {
            labels.add(metadata.getColumnLabel(index));
        }
        return labels;
    }

    private static List<Map<String, String>> rowsOf(ResultSet result) throws SQLException {
        List<String> labels = labels(result);
        List<Map<String, String>> rows = new ArrayList<>();
        while (result.next()) {
            Map<String, String> row = new LinkedHashMap<>();
            for (String label : labels) {
                row.put(label, result.getString(label));
            }
            rows.add(row);
        }
        return rows;
    }

    private void insertForecast(String observation) throws SQLException {
        execute("""
            INSERT INTO seat_forecast(vehicle_observation_id,target_stop_order,route_version_id,stops_to_target,
                model_deployment_id,demand_statistics_revision,seat_full_chance_raw,seat_full_chance,generated_at,
                publication_id,seat_target,probability_target,label_version,feature_snapshot,inference_bundle_hash)
            SELECT %s,3,1,1,1,0,0.5,0.5,now(),id,'AFTER_ALIGHTING_ESTIMATE','DEPARTURE_ZERO',
                'alighting-identity-safe-v1','{}'::jsonb,repeat('c',64)
            FROM forecast_publication ORDER BY id LIMIT 1
            """.formatted(observation));
    }

    private void assertLegacyObjectsPresent() throws SQLException {
        for (String relation : concat(LEGACY_TABLES, LEGACY_VIEWS)) {
            assertThat(scalar("SELECT to_regclass('public." + relation + "')::text")).as(relation).isNotNull();
        }
        assertThat(number("SELECT count(*) FROM seat_forecast")).isOne();
    }

    private String keptRows() throws SQLException {
        StringBuilder rows = new StringBuilder();
        for (String table : KEPT_TABLES) {
            rows.append(table).append('=').append(scalar("SELECT md5(coalesce(string_agg(to_jsonb(t)::text, ',' "
                + "ORDER BY to_jsonb(t)::text), '')) FROM " + table + " t")).append(';');
        }
        return rows.toString();
    }

    private String rows(String tableAndFilter) throws SQLException {
        return scalar("SELECT string_agg((to_jsonb(t) - 'source_date')::text, ',' "
            + "ORDER BY (to_jsonb(t) ->> 'id')::bigint) FROM " + tableAndFilter.replaceFirst("^(\\w+)", "$1 t"));
    }

    /** 테이블·뷰 이름과 사용자 트리거·함수 정의를 한 글로 낸다. 틀 DB에만 있는 테이블은 뺀다. */
    private static String schemaShape(Connection target) throws SQLException {
        StringBuilder shape = new StringBuilder();
        try (PreparedStatement tables = target.prepareStatement(TABLE_NAMES)) {
            tables.setArray(1, target.createArrayOf("text", OPERATIONS_ONLY_TABLES));
            try (ResultSet row = tables.executeQuery()) {
                row.next();
                shape.append("tables=").append(row.getString(1)).append('\n');
            }
        }
        for (String query : List.of(VIEW_NAMES, TRIGGER_DEFINITIONS, FUNCTION_DEFINITIONS)) {
            try (Statement statement = target.createStatement(); ResultSet row = statement.executeQuery(query)) {
                row.next();
                shape.append(row.getString(1)).append('\n');
            }
        }
        return shape.toString();
    }

    private String catalogFingerprint() throws SQLException {
        return scalar("SELECT md5(string_agg(c.oid::text || c.relname || c.relkind::text, ',' ORDER BY c.oid)) "
            + "FROM pg_class c WHERE c.relnamespace = 'public'::regnamespace") + scalar(
            "SELECT md5(string_agg(to_jsonb(t)::text, ',' ORDER BY id)) FROM observation_batch t");
    }

    private void runTableDrop(String... settings) throws IOException, SQLException {
        runConfirmed("sal434-03-drop-legacy-tables.sql", "sal434.confirm_drop", settings);
    }

    /** 파티션 전환 SQL을 실행하고, 그 SQL이 날짜 파티션을 만들 때 기준으로 삼은 한국 날짜를 돌려준다. */
    private LocalDate runPartitioning(String... settings) throws IOException, SQLException {
        return runConfirmed("sal434-04-partition-observation-tables.sql", "sal434.confirm_partition", settings);
    }

    private void run(String file, String... settings) throws IOException, SQLException {
        for (String setting : settings) {
            execute(setting);
        }
        try {
            for (String statement : statements(Files.readString(backend().resolve("deploy/sql").resolve(file)))) {
                execute(statement);
            }
        } catch (SQLException failure) {
            rollback();
            throw failure;
        }
    }

    /**
     * 승인값으로 오늘 한국 날짜를 받는 SQL 파일을 실행하고, 파일이 연 트랜잭션의 한국 날짜를 돌려준다. 실패하면 그 연결의
     * 트랜잭션을 되돌린다.
     *
     * @param confirmation 승인값을 담는 설정 이름
     */
    private LocalDate runConfirmed(String file, String confirmation, String... settings)
        throws IOException, SQLException {
        for (String setting : settings) {
            execute(setting);
        }
        try {
            return runConfirmed(connection, notices, file, confirmation);
        } catch (SQLException failure) {
            rollback();
            throw failure;
        }
    }

    /**
     * 승인값으로 오늘 한국 날짜를 받는 SQL 파일을 psql처럼 문장 단위로 실행하고, 파일이 연 트랜잭션의 한국 날짜를 돌려준다.
     *
     * <p>파일의 첫 문장 {@code BEGIN}을 실행한 뒤 그 트랜잭션의 한국 날짜를 승인값으로 넣는다. 파일이 승인값과 견주는 날짜도
     * 같은 트랜잭션의 {@code now()}에서 나오므로, 실행하는 사이 한국 날짜가 바뀌어도 둘은 같다.
     *
     * @param notices 서버가 알린 글을 받아 담는 목록
     * @param confirmation 승인값을 담는 설정 이름
     */
    static LocalDate runConfirmed(
        Connection target,
        List<String> notices,
        String file,
        String confirmation
    ) throws IOException, SQLException {
        List<String> script = statements(Files.readString(backend().resolve("deploy/sql").resolve(file)));
        assertThat(script.getFirst()).as(file + "의 첫 문장").isEqualToIgnoringCase("BEGIN");
        execute(target, notices, script.getFirst());
        LocalDate today = koreanDate(target);
        execute(target, notices, "SET " + confirmation + " = '" + today + "'");
        for (String statement : script.subList(1, script.size())) {
            execute(target, notices, statement);
        }
        return today;
    }

    /**
     * 연결이 열어 둔 트랜잭션이 시작한 시각의 한국 날짜다. 트랜잭션 안에서 {@code now()}는 변하지 않으므로, 같은 트랜잭션에서 넣는
     * 줄의 기본 {@code source_date}와 같은 날짜다. 트랜잭션을 열지 않았으면 이 조회를 실행한 시각의 날짜다.
     */
    static LocalDate koreanDate(Connection target) throws SQLException {
        try (Statement statement = target.createStatement(); ResultSet row = statement.executeQuery("SELECT now()")) {
            row.next();
            return row.getObject(1, OffsetDateTime.class).atZoneSameInstant(KOREA).toLocalDate();
        }
    }

    /**
     * 날짜 파티션의 이름과 경계에 든 날짜를 첫 날짜 파티션에서 며칠 뒤인지로 바꿔 쓴다. 날짜 파티션은 만든 날의 한국 날짜부터
     * 사흘 뒤까지라, 서로 다른 날짜에 만든 두 DB의 카탈로그도 이렇게 바꾸면 같은 글이 된다.
     *
     * @param first 그 DB의 첫 날짜 파티션의 날짜
     */
    static String relativeToFirstPartition(String catalog, LocalDate first) {
        String relative = catalog;
        for (int day = 0; day <= 4; day++) {
            LocalDate date = first.plusDays(day);
            relative = relative.replace("_p" + date.format(DateTimeFormatter.BASIC_ISO_DATE), "_p+" + day)
                .replace("'" + date + "'", "'+" + day + "'");
        }
        return relative;
    }

    /** 관측 배치 테이블의 날짜 파티션 가운데 가장 이른 날짜다. */
    static LocalDate firstPartitionDate(Connection target) throws SQLException {
        return LocalDate.parse(scalarOn(target, """
            SELECT min(to_date(substring(child.relname from '_p([0-9]{8})$'), 'YYYYMMDD'))
            FROM pg_inherits link JOIN pg_class child ON child.oid = link.inhrelid
            WHERE link.inhparent = 'observation_batch'::regclass
            """));
    }

    private void rollback() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("ROLLBACK");
        }
    }

    private void execute(String sql) throws SQLException {
        execute(connection, notices, sql);
    }

    private static void execute(Connection target, List<String> notices, String sql) throws SQLException {
        try (Statement statement = target.createStatement()) {
            statement.execute(sql);
            for (SQLWarning warning = statement.getWarnings(); warning != null; warning = warning.getNextWarning()) {
                notices.add(warning.getMessage());
            }
        }
    }

    /** 열어 둔 트랜잭션 안에서 실패할 문장을 실행하고 그 실패를 돌려준다. 저장점으로 되돌려 트랜잭션은 이어서 쓸 수 있다. */
    private Throwable failureInTransaction(String sql) throws SQLException {
        execute("SAVEPOINT attempt");
        Throwable failure = catchThrowable(() -> execute(sql));
        execute("ROLLBACK TO SAVEPOINT attempt");
        return failure;
    }

    private long number(String sql) throws SQLException {
        return Long.parseLong(scalar(sql));
    }

    private String scalar(String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    /** psql처럼 문장을 나눈다. 따옴표, 달러 따옴표, 주석 안의 세미콜론은 문장 끝이 아니다. */
    static List<String> statements(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int index = 0;
        while (index < script.length()) {
            final char c = script.charAt(index);
            if (c == '-' && script.startsWith("--", index)) {
                index = lineCommentEnd(script, index);
                continue;
            }
            if (c == '/' && script.startsWith("/*", index)) {
                index = script.indexOf("*/", index) + 2;
                continue;
            }
            if (c == '\'' || c == '"') {
                final int end = closingQuote(script, index, c);
                current.append(script, index, end + 1);
                index = end + 1;
                continue;
            }
            if (c == '$') {
                index = appendDollarQuoted(script, index, current);
                continue;
            }
            if (c == ';') {
                addStatement(statements, current);
                current.setLength(0);
                index++;
                continue;
            }
            current.append(c);
            index++;
        }
        addStatement(statements, current);
        return statements;
    }

    private static int lineCommentEnd(String script, final int index) {
        final int end = script.indexOf('\n', index);
        if (end < 0) {
            return script.length();
        }
        return end;
    }

    /** 두 번 겹친 따옴표는 문자열 안의 따옴표로 보고 건너뛴다. */
    private static int closingQuote(String script, final int start, final char quote) {
        int end = start + 1;
        while (true) {
            end = script.indexOf(quote, end);
            if (end + 1 < script.length() && script.charAt(end + 1) == quote) {
                end += 2;
                continue;
            }
            return end;
        }
    }

    /** 달러 따옴표면 닫는 태그까지, 아니면 {@code $} 한 글자만 붙이고 다음 위치를 돌려준다. */
    private static int appendDollarQuoted(String script, final int index, StringBuilder current) {
        Matcher tag = Pattern.compile("\\$[A-Za-z_]*\\$").matcher(script).region(index, script.length());
        if (!tag.lookingAt()) {
            current.append(script.charAt(index));
            return index + 1;
        }
        final int end = script.indexOf(tag.group(), tag.end()) + tag.group().length();
        current.append(script, index, end);
        return end;
    }

    private static void addStatement(List<String> statements, StringBuilder current) {
        if (!current.toString().isBlank()) {
            statements.add(current.toString().strip());
        }
    }

    private static synchronized TestDatabase template() throws Exception {
        if (template != null) {
            return template;
        }
        TestDatabase database = TestDatabase.create();
        flyway(database).target(BEFORE_RETIREMENT).load().migrate();
        try (Connection setup = database.connection(); Statement statement = setup.createStatement()) {
            statement.execute("""
                INSERT INTO route(id,public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name)
                    OVERRIDING SYSTEM VALUE VALUES (1,'p1','s1','sr1','1','a','b');
                INSERT INTO route_version(id,route_id,content_digest,valid_from) OVERRIDING SYSTEM VALUE
                    VALUES (1,1,repeat('0',64),'2026-01-01T00:00:00Z');
                INSERT INTO route_stop(route_version_id,stop_order,stop_id,name,direction,boarding_allowed)
                    SELECT 1,n,'st'||n,'s'||n,'UP',true FROM generate_series(1,3) n;
                INSERT INTO model_deployment(deployment_key,release_id,model_key,model_version,bundle_digest,
                    prediction_target_version,calculation_version,supported_scope_digest,data_until,state,activated_at)
                VALUES('00000000-0000-0000-0000-000000000001','release1','seat','1',repeat('1',64),
                    'target1','calc1',repeat('2',64),'2026-08-01T00:00:00Z','ACTIVE','2026-08-02T00:00:00Z');
                INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,outcome,
                    normalization_version,collection_strategy_version,ingestion_origin,response_received_at,
                    input_confirmed_at,stored_rows)
                SELECT 1,timestamptz '2026-11-01T00:00:00Z'+n*interval '20 seconds',1,'k'||n,'SUCCESS_ROWS','n1','c1',
                    'LIVE',timestamptz '2026-11-01T00:00:01Z'+n*interval '20 seconds',
                    timestamptz '2026-11-01T00:00:02Z'+n*interval '20 seconds',2
                FROM generate_series(1,3) n;
                INSERT INTO vehicle_observation(observation_batch_id,route_version_id,source_row_number,vehicle_id,
                    stop_order,stop_id,running_state,remaining_seats,passed_stop_order)
                SELECT b.id,1,r,'v'||r,1+r,'st'||(1+r),1,10,0 FROM observation_batch b CROSS JOIN generate_series(0,1) r;
                INSERT INTO forecast_publication(source_batch_id,route_version_id,model_deployment_id,
                    demand_statistics_revision,quality_revision,observed_at,generated_at,published_at,prediction_count)
                VALUES(1,1,1,0,1,now()-interval '2 hours',now()-interval '2 hours',now()-interval '2 hours',1);
                INSERT INTO seat_forecast(vehicle_observation_id,target_stop_order,route_version_id,stops_to_target,
                    model_deployment_id,demand_statistics_revision,seat_full_chance_raw,seat_full_chance,generated_at,
                    publication_id,seat_target,probability_target,label_version,feature_snapshot,inference_bundle_hash)
                SELECT 1,2,1,1,1,0,0.5,0.5,now()-interval '2 hours',id,'AFTER_ALIGHTING_ESTIMATE','DEPARTURE_ZERO',
                    'alighting-identity-safe-v1','{}'::jsonb,repeat('c',64) FROM forecast_publication;
                INSERT INTO stop_pass VALUES (DATE '2026-11-01',1,1,2,1,'v0',1,DATE '2026-11-01',1,10,now(),now(),
                    1,now(),2,9,now(),now(),NULL,4,2,now());
                INSERT INTO forecast_record VALUES (DATE '2026-11-01',1,1,0,1,1,'v0',1,1,1,false,45,false,
                    ARRAY[2],ARRAY[1.5]::float8[],ARRAY[0.1]::float8[]);
                INSERT INTO pass_label VALUES ((now() AT TIME ZONE 'Asia/Seoul')::date,1,1,2,45,'label1',1,'SETTLED',
                    NULL,1.5,NULL,NULL,NULL,now());
                -- 운영에만 있는 객체. 열 이름은 흉내다.
                CREATE TABLE historical_import_batch (id bigint PRIMARY KEY);
                INSERT INTO historical_import_batch VALUES (7);
                ALTER TABLE observation_batch ADD COLUMN historical_import_batch_id bigint
                    CONSTRAINT fk_observation_batch_historical_import REFERENCES historical_import_batch(id);
                CREATE INDEX ix_observation_batch_historical_import ON observation_batch (historical_import_batch_id);
                CREATE UNIQUE INDEX ux_observation_batch_historical_semantic
                    ON observation_batch (historical_import_batch_id, route_version_id, scheduled_at)
                    WHERE historical_import_batch_id IS NOT NULL;
                UPDATE observation_batch SET historical_import_batch_id = 7 WHERE attempt_key = 'k3';
                CREATE TABLE historical_import_record (id bigint PRIMARY KEY, target_batch_id bigint,
                    CONSTRAINT fk_historical_import_record_target FOREIGN KEY (target_batch_id) REFERENCES observation_batch(id));
                CREATE INDEX ix_archive_historical_import_target_batch_fk ON historical_import_record (target_batch_id);
                CREATE TABLE migration_source_record (id bigint PRIMARY KEY, observation_batch_id bigint,
                    CONSTRAINT fk_migration_source_observation_batch FOREIGN KEY (observation_batch_id)
                        REFERENCES observation_batch(id));
                INSERT INTO historical_import_record VALUES (1, 1);
                INSERT INTO migration_source_record VALUES (1, 2);
                """);
            for (String index : OPERATIONS_ONLY_INDEXES) {
                statement.execute(index);
            }
        }
        flyway(database).load().migrate();
        template = database;
        return template;
    }

    private static FluentConfiguration flyway(TestDatabase database) {
        return Flyway.configure().dataSource(database.jdbcUrl(), database.username(), database.password());
    }

    private static String scalarOn(Connection target, String sql) throws SQLException {
        try (Statement statement = target.createStatement(); ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getString(1);
        }
    }

    private static void admin(TestDatabase database, String sql) throws SQLException {
        String maintenance = database.jdbcUrl().replace(databaseName(database), "postgres");
        try (Connection connection =
                DriverManager.getConnection(maintenance, database.username(), database.password());
            Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String databaseName(TestDatabase database) {
        String url = database.jdbcUrl();
        return url.substring(url.lastIndexOf('/') + 1);
    }

    static Path backend() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null
            && !Files.isRegularFile(directory.resolve("deploy/sql/sal434-01-precheck.sql"))) {
            directory = directory.getParent();
        }
        if (directory == null) {
            throw new IllegalStateException("backend 디렉터리를 찾을 수 없다");
        }
        return directory;
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }
}
