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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.assertj.core.api.SoftAssertions;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SAL-434 운영 전환 수동 SQL({@code deploy/sql/sal434-*.sql})과 배포 가드의 DB 판정을 PostgreSQL 18에서 확인한다.
 *
 * <p>마이그레이션을 끝까지 적용한 DB에 운영에만 있는 객체(수동 인덱스, 옛 이관 기록 테이블과 그 FK, 배치의 이관 묶음 FK)를
 * 흉내 내고 옛 경로의 줄을 넣은 것을 원본으로 한 번 만든다. 시험마다 그 원본을 복제한 새 DB를 쓴다. SQL 파일은 psql처럼
 * 한 연결에서 문장 단위로 실행하고, 실패하면 그 연결의 트랜잭션을 되돌린다.
 */
class ForecastPipelineTransitionSqlTest {
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
        softly.assertThat(actual).hasMessageContaining("옛 정산이");
        softly.assertThat(number(LEGACY_TRIGGERS)).isEqualTo(9);
        softly.assertThat(number(LEGACY_FUNCTIONS)).isEqualTo(7);
        softly.assertAll();
    }

    @Test
    void 옛_예보가_아직_발행하거나_새_채점이_돌지_않으면_트리거를_지우지_않는다() throws Exception {
        // given
        execute("UPDATE forecast_publication SET published_at = now()");

        // when
        Throwable published = catchThrowable(() -> run("sal434-02-drop-legacy-triggers.sql", FAST));

        // then
        assertThat(published).hasMessageContaining("옛 예보가");

        // given
        execute("UPDATE forecast_publication SET published_at = now() - interval '2 hours'; DELETE FROM stop_pass");

        // when
        Throwable notScoring = catchThrowable(() -> run("sal434-02-drop-legacy-triggers.sql", FAST));

        // then
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(notScoring).hasMessageContaining("새 채점");
        softly.assertThat(number(LEGACY_TRIGGERS)).isEqualTo(9);
        softly.assertAll();
    }

    @Test
    void 승인값이_없거나_옛_정산_트리거가_남아_있으면_옛_테이블을_지우지_않는다() throws Exception {
        // when
        Throwable withTriggers = catchThrowable(() -> run("sal434-03-drop-legacy-tables.sql", FAST, confirmDrop()));

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
    void 옛_경로의_최근_흔적이_있으면_옛_테이블을_지우지_않는다() throws Exception {
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
        Throwable actual = catchThrowable(() -> run("sal434-03-drop-legacy-tables.sql", FAST, confirmDrop()));

        // then
        assertThat(actual).hasMessageContaining("옛 이관 앱");
        assertLegacyObjectsPresent();
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
            actual = catchThrowable(() -> run("sal434-03-drop-legacy-tables.sql",
                "SET sal434.observe_seconds = '4'", confirmDrop()));
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
        Throwable foreignKey = catchThrowable(() -> run("sal434-03-drop-legacy-tables.sql", FAST, confirmDrop()));

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
        Throwable view = catchThrowable(() -> run("sal434-03-drop-legacy-tables.sql", FAST, confirmDrop()));

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
        Throwable unverified = catchThrowable(() -> run("sal434-03-drop-legacy-tables.sql", FAST, confirmDrop()));

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
        Throwable removed = catchThrowable(() -> run("sal434-03-drop-legacy-tables.sql", FAST, confirmDrop()));

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
        Throwable gap = catchThrowable(() -> run("sal434-03-drop-legacy-tables.sql", FAST, confirmDrop()));

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
        run("sal434-03-drop-legacy-tables.sql", FAST, confirmDrop());

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
        run("sal434-03-drop-legacy-tables.sql", FAST, confirmDrop());

        // then
        assertThat(keptRows()).isEqualTo(kept);
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
        Throwable actual = catchThrowable(() -> run("sal434-04-partition-observation-tables.sql",
            confirmPartition(), "SET sal434.count_rows = 'exact'"));

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
        Throwable actual =
            catchThrowable(() -> run("sal434-04-partition-observation-tables.sql", confirmPartition()));

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
            + "WHERE oid = 'vehicle_observation_legacy'::regclass"))
            .isEqualTo("FOR VALUES FROM ('2000-01-01') TO ('2000-01-02')");
        softly.assertAll();
        String today = today().replace("-", "");
        execute("""
            INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,outcome,
                normalization_version,collection_strategy_version)
            VALUES(1,now(),1,'after-04','RESERVED','n1','c1')
            """);
        assertThat(scalar("SELECT tableoid::regclass::text FROM observation_batch WHERE attempt_key = 'after-04'"))
            .isEqualTo("observation_batch_p" + today);
        execute("""
            INSERT INTO vehicle_observation(observation_batch_id,route_version_id,source_row_number,vehicle_id,
                stop_order,stop_id,running_state,remaining_seats,passed_stop_order)
            SELECT id,1,0,'bus1',1,'st1',1,5,0 FROM observation_batch WHERE attempt_key = 'after-04'
            """);
        assertThat(scalar("SELECT tableoid::regclass::text || ':' || (id > " + maxObservation
            + ") FROM vehicle_observation WHERE vehicle_id = 'bus1' AND source_date <> '2000-01-01'"))
            .isEqualTo("vehicle_observation_p" + today + ":true");
        assertThat(number("SELECT count(*) FROM operational_observation")).isEqualTo(7);
        assertThat(catchThrowable(() -> execute("""
            INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,outcome,
                normalization_version,collection_strategy_version)
            VALUES(1,now(),1,'after-04','RESERVED','n1','c1')
            """))).hasMessageContaining("duplicate key");
        assertThat(catchThrowable(() -> execute("""
            INSERT INTO vehicle_observation(observation_batch_id,route_version_id,source_row_number,vehicle_id,
                stop_order,stop_id,running_state,remaining_seats,passed_stop_order)
            SELECT id,1,0,'bus2',1,'st1',1,5,0 FROM observation_batch WHERE attempt_key = 'after-04'
            """))).hasMessageContaining("duplicate key");
        assertThat(catchThrowable(() -> execute("""
            INSERT INTO vehicle_observation(observation_batch_id,route_version_id,source_row_number,vehicle_id,
                stop_order,stop_id,running_state,remaining_seats,passed_stop_order)
            SELECT id,1,1,'bus1',1,'st1',1,5,0 FROM observation_batch WHERE attempt_key = 'after-04'
            """))).hasMessageContaining("duplicate key");
        // 유일성은 날짜 파티션 안에서만 지킨다. 옛 줄과 같은 시도 키도 다른 날짜면 들어간다(파일 머리 주석의 뜻 변화).
        execute("""
            INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,outcome,
                normalization_version,collection_strategy_version)
            VALUES(1,now(),1,'k1','RESERVED','n1','c1')
            """);
        assertThat(number("SELECT count(*) FROM observation_batch WHERE attempt_key = 'k1'")).isEqualTo(2);
        Flyway flyway = Flyway.configure().dataSource(url, template.username(), template.password()).load();
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();

        // when
        run("sal434-04-partition-observation-tables.sql", confirmPartition(), "SET sal434.count_rows = 'exact'");

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
        run("sal434-04-rollback-unpartition-observation-tables.sql",
            "SET sal434.confirm_partition_rollback = '" + today() + "'");

        // then
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(unconfirmed).hasMessageContaining("승인값");
        softly.assertThat(scalar("SELECT relkind::text FROM pg_class WHERE oid = 'observation_batch'::regclass"))
            .isEqualTo("r");
        softly.assertThat(scalar("SELECT relkind::text FROM pg_class WHERE oid = 'vehicle_observation'::regclass"))
            .isEqualTo("r");
        softly.assertThat(scalar("SELECT to_regclass('public.observation_batch_legacy')::text")).isNull();
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
        run("sal434-04-partition-observation-tables.sql", confirmPartition(), "SET sal434.count_rows = 'exact'");

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
        run("sal434-03-drop-legacy-tables.sql", FAST, confirmDrop());
    }

    private void partitionObservations(String countMode) throws Exception {
        run("drop-historical-import-batch-fks.sql");
        run("sal434-04a-prepare-observation-partition.sql");
        run("sal434-04-partition-observation-tables.sql", confirmPartition(),
            "SET sal434.count_rows = '" + countMode + "'");
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

    /** 옛 테이블 내보내기가 남기는 모양의 검증된 조각 한 줄이다. 키 범위 값은 장부 대조에 쓰이지 않아 흉내만 낸다. */
    private void writeSegment(String table, final int segment, final long rows) throws SQLException {
        execute("INSERT INTO daily_export(table_name, source_date, segment, object_key, row_count, sha256, exported_at,"
            + " verified_at, key_from, key_to) VALUES ('" + table + "', DATE '2000-01-01', " + segment + ", 'legacy/"
            + table + "/segment=" + segment + "/manifest.json', " + rows + ", repeat('a', 64), now(), now(), '["
            + segment + "]', '[" + segment + "]')");
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

    private String triggerDefinitions() throws SQLException {
        return scalar("SELECT string_agg(pg_get_triggerdef(oid), E'\\n' ORDER BY pg_get_triggerdef(oid)) "
            + "FROM pg_trigger WHERE NOT tgisinternal");
    }

    private String functionDefinitions() throws SQLException {
        return scalar("SELECT string_agg(pg_get_functiondef(p.oid), E'\\n' ORDER BY p.oid::regprocedure::text) "
            + "FROM pg_proc p WHERE p.pronamespace = 'public'::regnamespace AND p.prokind = 'f' "
            + "AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid = 'pg_proc'::regclass AND d.objid = p.oid "
            + "AND d.deptype = 'e')");
    }

    private String catalogFingerprint() throws SQLException {
        return scalar("SELECT md5(string_agg(c.oid::text || c.relname || c.relkind::text, ',' ORDER BY c.oid)) "
            + "FROM pg_class c WHERE c.relnamespace = 'public'::regnamespace") + scalar(
            "SELECT md5(string_agg(to_jsonb(t)::text, ',' ORDER BY id)) FROM observation_batch t");
    }

    private String confirmDrop() throws SQLException {
        return "SET sal434.confirm_drop = '" + today() + "'";
    }

    private String confirmPartition() throws SQLException {
        return "SET sal434.confirm_partition = '" + today() + "'";
    }

    private String today() throws SQLException {
        return scalar("SELECT to_char((now() AT TIME ZONE 'Asia/Seoul')::date, 'YYYY-MM-DD')");
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
            try (Statement statement = connection.createStatement()) {
                statement.execute("ROLLBACK");
            }
            throw failure;
        }
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
            for (SQLWarning warning = statement.getWarnings(); warning != null; warning = warning.getNextWarning()) {
                notices.add(warning.getMessage());
            }
        }
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
        Flyway.configure().dataSource(database.jdbcUrl(), database.username(), database.password()).load().migrate();
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
        template = database;
        return template;
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

    private static Path backend() {
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
