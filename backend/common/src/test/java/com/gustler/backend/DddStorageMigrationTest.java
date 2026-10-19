package com.gustler.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class DddStorageMigrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18");

    private String schema;
    private Connection connection;

    @BeforeEach
    void 전환마다_독립된_스키마를_사용한다() throws SQLException {
        schema = "transition_" + UUID.randomUUID().toString().replace("-", "");
        connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        execute("CREATE SCHEMA " + schema);
        execute("SET search_path TO " + schema + ", public");
    }

    @AfterEach
    void 시험용_스키마를_삭제한다() throws SQLException {
        execute("DROP SCHEMA " + schema + " CASCADE");
        connection.close();
    }

    @Test
    void 빈_DB는_별도_데이터_이관_없이_최종_구조로_설치한다() throws SQLException {
        migrate("26");

        assertThat(number("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '" + schema
            + "' AND table_name IN ('forecast_publication','forecast_evaluation','demand_statistics_version',"
            + "'route_data_quality','route_version_quality_policy','observation_trip_assignment')")).isEqualTo(6);
        assertThat(number("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = '" + schema
            + "' AND table_name = 'seat_forecast' AND column_name = 'scoring_state'")).isZero();
    }

    @Test
    void 최종_구조에서는_통계_발행_기록을_세대_표로_합치고_평가_표의_vacuum_기준을_낮춘다() throws SQLException {
        migrate("26");

        assertThat(number("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '" + schema
            + "' AND table_name = 'stop_demand_publication'")).isZero();
        assertThat(scalar("SELECT array_to_string(reloptions, ',') FROM pg_class WHERE oid = 'forecast_evaluation'::regclass"))
            .isEqualTo("autovacuum_vacuum_scale_factor=0.01,autovacuum_vacuum_threshold=50,"
                + "autovacuum_vacuum_max_threshold=10000");
    }

    @Test
    void 예보_표의_vacuum_시작_기준은_최종_구조에도_남는다() throws SQLException {
        migrate("26");

        assertThat(scalar("SELECT array_to_string(reloptions, ',') FROM pg_class WHERE oid = 'seat_forecast'::regclass"))
            .isEqualTo("autovacuum_vacuum_scale_factor=0.01,autovacuum_vacuum_threshold=50,"
                + "autovacuum_vacuum_max_threshold=10000");
    }

    @Test
    void 검증_이후_통계_누적_자료가_바뀌어도_검증_완료를_취소한다() throws SQLException {
        migrate("24");
        insertRoute();
        execute("INSERT INTO route_version(route_id,content_digest,valid_from) VALUES(1,repeat('0',64),'2026-01-01T00:00:00Z')");
        migrate("25");
        execute("INSERT INTO sal134_transition(backup_id, verified_at) VALUES ('stopped-writer-backup', now())");

        execute("INSERT INTO stop_demand_baseline(route_version_id) VALUES (1)");

        assertThat(number("SELECT COUNT(*) FROM sal134_transition WHERE verified_at IS NOT NULL")).isZero();
        assertThatThrownBy(() -> migrate("26")).hasMessageContaining("backfill 검증");
    }

    @Test
    void 검증하지_않은_기존_DB에서는_컬럼을_삭제하기_전에_전환을_거절한다() throws SQLException {
        migrate("24");
        insertRoute();
        migrate("25");

        assertThatThrownBy(() -> migrate("26")).hasMessageContaining("backfill 검증");
        assertThat(number("SELECT quality_revision FROM route WHERE id = 1")).isEqualTo(7);
        assertThat(number("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = '" + schema
            + "' AND table_name = 'seat_forecast' AND column_name = 'scoring_state'")).isOne();
    }

    @Test
    void 검증_이후_변경이_발생하면_검증_완료를_취소한다() throws SQLException {
        migrate("24");
        insertRoute();
        migrate("25");
        execute("INSERT INTO sal134_transition(backup_id, verified_at) VALUES ('stopped-writer-backup', now())");

        execute("UPDATE route SET display_name = '변경된 노선' WHERE id = 1");

        assertThat(number("SELECT COUNT(*) FROM sal134_transition WHERE verified_at IS NOT NULL")).isZero();
        assertThatThrownBy(() -> migrate("26")).hasMessageContaining("backfill 검증");
    }

    @Test
    void 예측_평가와_품질을_분리해도_원관측과_학습_조회_값을_보존한다() throws SQLException {
        migrate("24");
        insertLegacyData();
        String observationBefore = scalar("SELECT string_agg(row_to_json(o)::text, ',' ORDER BY id) FROM vehicle_observation o");
        String viewColumnsBefore = viewColumns();
        String forecastsBefore = scalar("SELECT string_agg(row_to_json(f)::text, ',' ORDER BY vehicle_observation_id,target_stop_order) FROM quality_training_seat_forecast f");
        migrate("25");
        copyLegacyData();
        execute("INSERT INTO sal134_transition(backup_id, verified_at) VALUES ('stopped-writer-backup', now())");

        migrate("26");

        assertThat(scalar("SELECT string_agg(row_to_json(o)::text, ',' ORDER BY id) FROM vehicle_observation o")).isEqualTo(observationBefore);
        assertThat(scalar("SELECT string_agg(row_to_json(f)::text, ',' ORDER BY vehicle_observation_id,target_stop_order) FROM quality_training_seat_forecast f")).isEqualTo(forecastsBefore);
        assertThat(viewColumns()).isEqualTo(viewColumnsBefore);
        assertThat(number("SELECT COUNT(*) FROM training_eligible_seat_forecast")).isOne();
        assertThat(number("SELECT COUNT(*) FROM forecast_evaluation WHERE scoring_state = 'SETTLED'")).isOne();
        assertThat(number("SELECT quality_revision FROM route_data_quality WHERE route_id = 1")).isEqualTo(7);
        assertThat(number("SELECT COUNT(*) FROM forecast_publication WHERE provenance = 'LEGACY_UNKNOWN' AND model_deployment_id IS NULL")).isOne();
        assertThat(number("SELECT COUNT(*) FROM observation_batch WHERE input_confirmed_at IS NOT NULL")).isEqualTo(3);
    }

    @Test
    void 평가_근거를_보존하면서_도착_관측의_최신_편도_판정을_조회에_반영한다() throws SQLException {
        migrate("24");
        insertLegacyData();
        migrate("25");
        copyLegacyData();
        execute("INSERT INTO sal134_transition(backup_id, verified_at) VALUES ('stopped-writer-backup', now())");
        migrate("26");

        execute("""
            INSERT INTO vehicle_one_way_trip(id,start_observation_id,route_version_id,vehicle_id,status,boundary,rule_version)
            VALUES('arrival-trip',2,1,'bus1','EXCLUDED','START','quality1');
            INSERT INTO observation_trip_assignment(observation_id,trip_id) VALUES(2,'arrival-trip')
            """);

        assertThat(number("SELECT COUNT(*) FROM training_eligible_seat_forecast")).isZero();
        assertThat(scalar("SELECT arrival_vehicle_trip_key FROM forecast_evaluation")).isEqualTo("legacy-unassigned");
        execute("UPDATE vehicle_one_way_trip SET status='ELIGIBLE' WHERE id='arrival-trip'");
        assertThat(number("SELECT COUNT(*) FROM training_eligible_seat_forecast")).isOne();
        execute("""
            INSERT INTO trip_quality_rebuild(route_version_id,vehicle_id,until_at,phase)
            VALUES(1,'bus1','2026-09-02T00:00:00Z','SEARCH_START')
            """);
        assertThat(number("SELECT COUNT(*) FROM training_eligible_seat_forecast")).isZero();
        execute("UPDATE trip_quality_rebuild SET completed=true,phase='DONE'");
        assertThat(number("SELECT COUNT(*) FROM training_eligible_seat_forecast")).isOne();
    }

    @Test
    void 과거_빈_발행은_모델을_추정하지_않고_출처를_모름으로_기록한다() throws SQLException {
        migrate("24");
        insertLegacyData();
        migrate("25");

        execute("""
            INSERT INTO forecast_publication(source_batch_id, route_version_id,
                observed_at, published_at, prediction_count, provenance)
            VALUES (3, 1, '2026-09-01T00:02:00Z', '2026-09-01T00:02:01Z', 0, 'LEGACY_UNKNOWN')
            """);
        assertThat(number("SELECT COUNT(*) FROM forecast_publication WHERE model_deployment_id IS NULL")).isOne();
        assertThatThrownBy(() -> execute("""
            INSERT INTO forecast_publication(source_batch_id, route_version_id,
                observed_at, published_at, prediction_count, provenance)
            VALUES (2, 1, '2026-09-01T00:01:00Z', '2026-09-01T00:01:01Z', 0, 'RECORDED')
            """)).isInstanceOf(SQLException.class);
    }

    @Test
    void 평가_분리는_완료_이력_파일을_유지하고_대기만_옮긴다() throws SQLException {
        preparePendingTransition();
        long originalFile = number("SELECT pg_relation_filenode('forecast_evaluation')");
        long trainingRows = number("SELECT count(*) FROM training_eligible_seat_forecast");

        migrate("31");

        assertThat(number("SELECT pg_relation_filenode('forecast_evaluation_result')")).isEqualTo(originalFile);
        assertThat(number("SELECT count(*) FROM forecast_evaluation_result")).isOne();
        assertThat(number("SELECT count(*) FROM forecast_evaluation_pending")).isOne();
        assertThat(number("SELECT count(*) FROM forecast_evaluation")).isEqualTo(2);
        assertThat(number("SELECT count(*) FROM quality_eligible_seat_forecast")).isEqualTo(2);
        assertThat(number("SELECT count(*) FROM training_eligible_seat_forecast")).isEqualTo(trainingRows);
        assertThat(scalar("SELECT scoring_state FROM forecast_evaluation WHERE target_stop_order=3")).isEqualTo("PENDING");
        assertThat(number("SELECT seats_on_arrival FROM forecast_evaluation_result")).isEqualTo(9);
        assertThatThrownBy(() -> execute("""
            INSERT INTO forecast_evaluation_result(vehicle_observation_id,target_stop_order,route_version_id,scoring_state)
            VALUES(1,3,1,'PENDING')
            """)).isInstanceOf(SQLException.class);
    }

    @Test
    void 관측별_작업으로_전환해도_미완료_예보와_완료_이력은_그대로다() throws Exception {
        // given
        preparePendingTransition();
        migrate("38");
        String before = scalar("SELECT string_agg(vehicle_observation_id || ':' || target_stop_order, ',' ORDER BY vehicle_observation_id,target_stop_order) FROM forecast_evaluation_pending");
        long resultFile = number("SELECT pg_relation_filenode('forecast_evaluation_result')");
        approveEvaluationTransition();

        // when
        migrate("39");

        // then
        assertThat(scalar("SELECT string_agg(vehicle_observation_id || ':' || target_stop_order, ',' ORDER BY vehicle_observation_id,target_stop_order) FROM forecast_evaluation_pending")).isEqualTo(before);
        assertThat(number("SELECT count(*) FROM forecast_evaluation_work")).isOne();
        assertThat(number("SELECT pg_relation_filenode('forecast_evaluation_result')")).isEqualTo(resultFile);
        assertThat(scalar("SELECT relkind::text FROM pg_class WHERE oid='forecast_evaluation_pending'::regclass")).isEqualTo("v");
        assertThat(scalar("SELECT to_regclass('sal175_evaluation_transition')::text")).isNull();
    }

    @Test
    void 복원할_대기가_기존과_다르면_전환을_취소하고_기존_테이블을_남긴다() throws Exception {
        // given
        preparePendingTransition();
        migrate("38");
        execute("DELETE FROM forecast_evaluation_result");
        approveEvaluationTransition();

        // when
        assertThatThrownBy(() -> migrate("39")).hasMessageContaining("기존 대기와 관측별 복원 결과가 다르다");

        // then
        assertThat(scalar("SELECT relkind::text FROM pg_class WHERE oid='forecast_evaluation_pending'::regclass")).isEqualTo("r");
        assertThat(number("SELECT count(*) FROM forecast_evaluation_pending")).isOne();
        assertThat(scalar("SELECT to_regclass('forecast_evaluation_work')::text")).isNull();
    }

    @Test
    void 기존_대기가_이관_기록과_겹쳐_복원에서_빠지면_전환을_취소한다() throws Exception {
        // given
        preparePendingTransition();
        migrate("38");
        execute("""
            INSERT INTO evaluation_archive_batch(id,route_version_id,quality_revision,lease_token,lease_until,row_count)
            VALUES('00000000-0000-0000-0000-000000000002',1,7,
                   '00000000-0000-0000-0000-000000000003',now(),1);
            INSERT INTO evaluation_archive_member(vehicle_observation_id,target_stop_order,route_version_id,batch_id,original_sha256)
            VALUES(1,3,1,'00000000-0000-0000-0000-000000000002',repeat('a',64))
            """);
        approveEvaluationTransition();

        // when
        assertThatThrownBy(() -> migrate("39"))
            .hasMessageContaining("기존 대기와 관측별 복원 결과가 다르다");

        // then
        assertThat(number("SELECT count(*) FROM forecast_evaluation_pending")).isOne();
        assertThat(scalar("SELECT to_regclass('forecast_evaluation_work')::text")).isNull();
    }

    @Test
    void 개발_DB의_V33에서_승인_후_V39까지_한번에_전환해도_대기와_완료_평가를_보존한다() throws Exception {
        // given
        preparePendingTransition();
        migrate("33");
        long pending = number("SELECT count(*) FROM forecast_evaluation_pending");
        long completed = number("SELECT count(*) FROM forecast_evaluation_result");
        approveEvaluationTransition();

        // when
        migrate("39");
        migrate("39");

        // then
        assertThat(number("SELECT count(*) FROM forecast_evaluation_pending")).isEqualTo(pending);
        assertThat(number("SELECT count(*) FROM forecast_evaluation_result")).isEqualTo(completed);
        assertThat(number("SELECT count(*) FROM forecast_evaluation_work")).isOne();
        assertThat(scalar("SELECT relkind::text FROM pg_class WHERE oid='forecast_evaluation_pending'::regclass"))
            .isEqualTo("v");
        assertThat(scalar("SELECT to_regclass('sal175_evaluation_transition')::text")).isNull();
    }

    @Test
    void 전환_검증은_전체_예보를_읽지_않고_대기_관측의_기존_인덱스를_사용한다() throws Exception {
        // given: 실제 데이터가 아닌 10만 건의 합성 예보 중 마지막 관측의 10건만 대기한다.
        execute("""
            CREATE TABLE seat_forecast(vehicle_observation_id bigint, target_stop_order integer,
                route_version_id bigint, PRIMARY KEY(vehicle_observation_id,target_stop_order));
            CREATE TABLE forecast_evaluation_result(LIKE seat_forecast INCLUDING INDEXES);
            CREATE TABLE evaluation_archive_member(LIKE seat_forecast INCLUDING INDEXES);
            CREATE TABLE forecast_evaluation_work(vehicle_observation_id bigint PRIMARY KEY, route_version_id bigint);
            CREATE TABLE forecast_evaluation_pending_legacy(LIKE seat_forecast INCLUDING INDEXES);
            INSERT INTO seat_forecast SELECT (n-1)/10+1,(n-1)%10+1,1 FROM generate_series(1,100000) n;
            INSERT INTO forecast_evaluation_work VALUES(10000,1);
            INSERT INTO forecast_evaluation_pending_legacy SELECT * FROM seat_forecast WHERE vehicle_observation_id=10000;
            ANALYZE seat_forecast;
            ANALYZE forecast_evaluation_work;
            ANALYZE forecast_evaluation_pending_legacy;
            ANALYZE forecast_evaluation_result;
            ANALYZE evaluation_archive_member
            """);
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V39__observation_evaluation_work.sql"));
        String verification = migration.substring(migration.indexOf("WITH restored AS MATERIALIZED"),
            migration.indexOf(" INTO mismatch;"));

        // when
        StringBuilder plan = new StringBuilder();
        try (var statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("EXPLAIN (ANALYZE, BUFFERS) " + verification)) {
            while (rows.next()) plan.append(rows.getString(1)).append('\n');
        }

        // then
        assertThat(scalar(verification)).isEqualTo("f");
        assertThat(plan.toString()).contains("seat_forecast_pkey")
            .doesNotContain("Seq Scan on seat_forecast");
        System.out.println("V39 synthetic verification plan:\n" + plan);
    }

    private void preparePendingTransition() throws SQLException {
        migrate("24");
        insertLegacyData();
        migrate("25");
        copyLegacyData();
        execute("INSERT INTO sal134_transition(backup_id, verified_at) VALUES ('stopped-writer-backup', now())");
        migrate("30");
        execute("""
            INSERT INTO route_stop(route_version_id,stop_order,stop_id,name,direction,boarding_allowed)
            VALUES(1,3,'stop3','다음 정류장','UP',true);
            INSERT INTO seat_forecast(vehicle_observation_id,target_stop_order,route_version_id,stops_to_target,
                model_deployment_id,demand_statistics_revision,seat_full_chance_raw,seat_full_chance,
                generated_at,quality_revision,publication_id)
            SELECT vehicle_observation_id,3,route_version_id,2,model_deployment_id,demand_statistics_revision,
                seat_full_chance_raw,seat_full_chance,generated_at,quality_revision,publication_id FROM seat_forecast;
            INSERT INTO forecast_evaluation(vehicle_observation_id,target_stop_order,route_version_id) VALUES(1,3,1)
            """);
    }

    @Test
    void V39_스키마를_이전_구조로_되돌린_뒤_최신으로_다시_전환해도_미완료_예보가_보존된다() throws Exception {
        // given
        preparePendingTransition();
        migrate("38");
        approveEvaluationTransition();
        migrate("39");
        java.nio.file.Path directory = java.nio.file.Path.of("").toAbsolutePath();
        while (directory != null && !java.nio.file.Files.isRegularFile(
            directory.resolve("deploy/sql/rollback-observation-evaluation-work.sql"))) directory = directory.getParent();
        if (directory == null) throw new IllegalStateException("롤백 SQL을 찾을 수 없다");
        String rollback = java.nio.file.Files.readString(directory.resolve("deploy/sql/rollback-observation-evaluation-work.sql"));

        // when
        execute(rollback);

        // then
        assertThat(scalar("SELECT relkind::text FROM pg_class WHERE oid='forecast_evaluation_pending'::regclass")).isEqualTo("r");
        assertThat(number("SELECT count(*) FROM forecast_evaluation_pending")).isOne();
        assertThat(number("SELECT count(*) FROM forecast_evaluation_result")).isOne();
        approveEvaluationTransition();
        migrate("latest");
        assertThat(number("SELECT count(*) FROM forecast_evaluation_work")).isOne();
        assertThat(number("SELECT count(*) FROM forecast_evaluation_pending")).isOne();
    }

    @Test
    void 최신_스키마에서도_로컬_초기화는_평가와_이관_기록을_비우고_노선은_보존한다() throws Exception {
        // given
        preparePendingTransition();
        migrate("38");
        approveEvaluationTransition();
        migrate("latest");
        execute("""
            INSERT INTO evaluation_archive_batch(id,route_version_id,quality_revision,lease_token,lease_until,row_count)
            VALUES('00000000-0000-0000-0000-000000000002',1,7,
                   '00000000-0000-0000-0000-000000000003',now(),1);
            INSERT INTO evaluation_archive_member(vehicle_observation_id,target_stop_order,route_version_id,batch_id,original_sha256)
            VALUES(1,2,1,'00000000-0000-0000-0000-000000000002',repeat('a',64));
            INSERT INTO file_statistics_publication(route_version_id,input_sha256,calculation_version,revision)
            VALUES(1,repeat('b',64),'calc1',1);
            UPDATE evaluation_archive_scan SET observation_id=1,stop_order=2,next_attempt_at=now()
            """);
        assertThat(number("SELECT count(*) FROM forecast_evaluation_result")).isOne();
        assertThat(number("SELECT count(*) FROM forecast_evaluation_work")).isOne();
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("dev/data/java/com/gustler/localdata/LocalData.java"))) {
            root = root.getParent();
        }
        assertThat(root).as("로컬 초기화 소스가 있는 저장소 루트").isNotNull();
        String source = Files.readString(root.resolve("dev/data/java/com/gustler/localdata/LocalData.java"));
        int start = source.indexOf("TRUNCATE ", source.indexOf("private static void resetGenerated"));
        assertThat(start).isGreaterThanOrEqualTo(0);
        int end = source.indexOf("\"\"\"", start);

        // when
        execute(source.substring(start, end));

        // then
        assertThat(number("SELECT count(*) FROM forecast_evaluation_work")).isZero();
        assertThat(number("SELECT count(*) FROM forecast_evaluation_pending")).isZero();
        assertThat(number("SELECT count(*) FROM forecast_evaluation_result")).isZero();
        assertThat(number("SELECT count(*) FROM evaluation_archive_member")).isZero();
        assertThat(number("SELECT count(*) FROM evaluation_archive_batch")).isZero();
        assertThat(number("SELECT count(*) FROM file_statistics_publication")).isZero();
        assertThat(number("SELECT count(*) FROM evaluation_archive_scan WHERE id=1 AND observation_id=0 AND stop_order=0 AND next_attempt_at='-infinity'::timestamptz")).isOne();
        assertThat(number("SELECT count(*) FROM route")).isOne();
    }

    @Test
    void 전환_승인이_없으면_기존_대기_테이블을_바꾸지_않는다() throws Exception {
        // given
        preparePendingTransition();
        migrate("38");

        // when
        assertThatThrownBy(() -> migrate("39")).hasMessageContaining("prepare로 전환을 승인");

        // then
        assertPendingTablePreserved();
    }

    @Test
    void 전환_승인이_만료되면_기존_대기_테이블을_바꾸지_않는다() throws Exception {
        // given
        preparePendingTransition();
        migrate("38");
        approveEvaluationTransition();
        execute("UPDATE sal175_evaluation_transition SET expires_at=clock_timestamp()-interval '1 second'");

        // when
        assertThatThrownBy(() -> migrate("39")).hasMessageContaining("prepare로 전환을 승인");

        // then
        assertPendingTablePreserved();
    }

    @Test
    void 승인_후_정비_연결이_생기면_전환을_중단한다() throws Exception {
        // given
        preparePendingTransition();
        migrate("38");
        approveEvaluationTransition();
        try (Connection maintenance = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = maintenance.createStatement()) {
            statement.execute("SET application_name='salmonbus-historical-migration'");

            // when
            assertThatThrownBy(() -> migrate("39")).hasMessageContaining("worker 또는 정비 연결이 남아 있다");

            // then
            assertPendingTablePreserved();
        }
        migrate("39");
        assertThat(number("SELECT count(*) FROM forecast_evaluation_work")).isOne();
    }

    @Test
    void worker_연결이_남아_있으면_전환을_승인하지_않는다() throws Exception {
        // given
        preparePendingTransition();
        migrate("38");
        try (Connection worker = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = worker.createStatement()) {
            statement.execute("SET application_name='salmonbus-worker'");

            // when
            assertThatThrownBy(this::approveEvaluationTransition)
                .hasMessageContaining("worker 또는 정비 연결이 남아 있다");
            execute("ROLLBACK");

            // then
            assertThat(scalar("SELECT to_regclass('sal175_evaluation_transition')::text")).isNull();
            assertPendingTablePreserved();
        }
    }

    private void assertPendingTablePreserved() throws SQLException {
        assertThat(scalar("SELECT relkind::text FROM pg_class WHERE oid='forecast_evaluation_pending'::regclass")).isEqualTo("r");
        assertThat(number("SELECT count(*) FROM forecast_evaluation_pending")).isOne();
        assertThat(scalar("SELECT to_regclass('forecast_evaluation_work')::text")).isNull();
    }

    @Test
    void worker_연결이_남아_있으면_이전_DB_구조로_되돌리지_않는다() throws Exception {
        // given
        preparePendingTransition();
        migrate("38");
        approveEvaluationTransition();
        migrate("39");
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.isRegularFile(directory.resolve("deploy/sql/rollback-observation-evaluation-work.sql"))) {
            directory = directory.getParent();
        }
        assertThat(directory).isNotNull();
        String rollback = Files.readString(directory.resolve("deploy/sql/rollback-observation-evaluation-work.sql"));
        try (Connection worker = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = worker.createStatement()) {
            statement.execute("SET application_name='salmonbus-worker'");

            // when
            assertThatThrownBy(() -> execute(rollback)).hasMessageContaining("worker 또는 정비 연결이 남아 있다");
            execute("ROLLBACK");

            // then
            assertThat(number("SELECT count(*) FROM forecast_evaluation_work")).isOne();
            assertThat(scalar("SELECT relkind::text FROM pg_class WHERE oid='forecast_evaluation_pending'::regclass")).isEqualTo("v");
            assertThat(number("SELECT count(*) FROM flyway_schema_history WHERE version='39' AND success")).isOne();
        }
    }

    private void approveEvaluationTransition() throws Exception {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.isRegularFile(directory.resolve("deploy/sql/prepare-evaluation-work-transition.sql"))) {
            directory = directory.getParent();
        }
        assertThat(directory).isNotNull();
        execute("SET salmonbus.backup_reference='isolated-test-backup'");
        execute(Files.readString(directory.resolve("deploy/sql/prepare-evaluation-work-transition.sql")));
    }

    private void insertRoute() throws SQLException {
        execute("""
            INSERT INTO route(public_route_id, source_id, source_route_id, display_name, start_stop_name, end_stop_name, quality_revision)
            VALUES ('3330', 'GBIS', '3330', '3330', '출발', '도착', 7)
            """);
    }

    private void insertLegacyData() throws SQLException {
        insertRoute();
        execute("""
            INSERT INTO route_version(route_id,content_digest,valid_from,maximum_observation_gap_seconds,observation_gap_evidence)
            VALUES(1,repeat('0',64),'2026-01-01T00:00:00Z',600,'운행 간격 확인');
            INSERT INTO route_stop(route_version_id,stop_order,stop_id,name,direction,boarding_allowed)
            VALUES(1,1,'stop1','출발','UP',true),(1,2,'stop2','도착','UP',true);
            INSERT INTO model_deployment(deployment_key,release_id,model_key,model_version,bundle_digest,
                prediction_target_version,calculation_version,supported_scope_digest,data_until,state,activated_at)
            VALUES('00000000-0000-0000-0000-000000000001','release1','seat','1',repeat('1',64),
                'target1','calc1',repeat('2',64),'2026-08-01T00:00:00Z','ACTIVE','2026-08-02T00:00:00Z');
            INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,response_received_at,
                forecast_completed_at,outcome,normalization_version,collection_strategy_version)
            VALUES(1,'2026-09-01T00:00:00Z',1,'source','2026-09-01T00:00:00Z','2026-09-01T00:00:01Z','SUCCESS_ROWS','n1','c1'),
                  (1,'2026-09-01T00:01:00Z',1,'arrival','2026-09-01T00:01:00Z',NULL,'SUCCESS_ROWS','n1','c1'),
                  (1,'2026-09-01T00:02:00Z',1,'empty','2026-09-01T00:02:00Z','2026-09-01T00:02:01Z','SUCCESS_EMPTY','n1','c1');
            INSERT INTO vehicle_observation(observation_batch_id,route_version_id,source_row_number,vehicle_id,vehicle_trip_key,
                stop_order,stop_id,running_state,remaining_seats,passed_stop_order)
            VALUES(1,1,0,'bus1','legacy-unassigned',1,'stop1',2,12,1),(2,1,0,'bus1','legacy-unassigned',2,'stop2',2,9,2);
            INSERT INTO seat_forecast(vehicle_observation_id,target_stop_order,route_version_id,stops_to_target,
                model_deployment_id,demand_statistics_revision,seat_full_chance_raw,seat_full_chance,generated_at,
                scoring_state,arrival_observation_id,seats_on_arrival,scored_at,quality_revision)
            VALUES(1,2,1,1,1,1,0.2,0.3,'2026-09-01T00:00:01Z','SETTLED',2,9,'2026-09-01T00:01:30Z',7);
            INSERT INTO stop_demand_statistics(route_version_id,stop_order,time_slot,calculation_version,revision,
                average_fill_rate,average_net_boarding_rate,sample_count,data_until,computed_at,day_count,quality_revision)
            VALUES(1,2,'morning','calc1',1,0.4,0.1,1,'2026-08-31T00:00:00Z','2026-08-31T01:00:00Z',1,7)
            """);
    }

    private void copyLegacyData() throws SQLException {
        execute("""
            INSERT INTO route_data_quality SELECT id, quality_revision FROM route;
            INSERT INTO route_version_quality_policy SELECT id,maximum_observation_gap_seconds,observation_gap_evidence FROM route_version;
            INSERT INTO demand_statistics_version(route_version_id,calculation_version,revision,data_until,computed_at,quality_revision,cell_count)
            SELECT route_version_id,calculation_version,revision,data_until,computed_at,quality_revision,count(*)
            FROM stop_demand_statistics GROUP BY route_version_id,calculation_version,revision,data_until,computed_at,quality_revision;
            INSERT INTO forecast_publication(source_batch_id,route_version_id,model_deployment_id,
                demand_statistics_revision,quality_revision,observed_at,generated_at,published_at,prediction_count)
            VALUES(1,1,1,1,7,'2026-09-01T00:00:00Z','2026-09-01T00:00:01Z','2026-09-01T00:00:01Z',1);
            INSERT INTO forecast_publication(source_batch_id,route_version_id,observed_at,published_at,prediction_count,provenance)
            VALUES(3,1,'2026-09-01T00:02:00Z','2026-09-01T00:02:01Z',0,'LEGACY_UNKNOWN');
            UPDATE seat_forecast SET publication_id = 1;
            INSERT INTO forecast_evaluation(vehicle_observation_id,target_stop_order,route_version_id,scoring_state,
                arrival_observation_id,seats_on_arrival,scored_at,arrived_at,arrival_route_version_id,arrival_vehicle_id,
                arrival_stop_order,arrival_running_state,arrival_remaining_seats,arrival_vehicle_trip_key,arrival_quality_direction)
            VALUES(1,2,1,'SETTLED',2,9,'2026-09-01T00:01:30Z','2026-09-01T00:01:00Z',1,'bus1',2,2,9,'legacy-unassigned',0);
            UPDATE observation_batch SET input_confirmed_at = COALESCE(forecast_completed_at,'2026-09-01T00:01:30Z')
            """);
    }

    private String viewColumns() throws SQLException {
        return scalar("""
            SELECT string_agg(c.relname || ':' || a.attname || ':' || format_type(a.atttypid,a.atttypmod),
                ',' ORDER BY c.relname,a.attnum)
            FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid
            JOIN pg_namespace n ON n.oid=c.relnamespace
            WHERE n.nspname=current_schema() AND a.attnum>0 AND NOT a.attisdropped
                AND c.relname IN ('forecast_observation_quality','forecast_eligible_observation',
                    'quality_eligible_seat_forecast','quality_calibration_seat_forecast','quality_training_seat_forecast')
            """);
    }

    private void migrate(final String target) {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(schema).defaultSchema(schema).target(target).load().migrate();
    }

    private void execute(final String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long number(final String sql) throws SQLException {
        return Long.parseLong(scalar(sql));
    }

    private String scalar(final String sql) throws SQLException {
        try (var statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }
}
