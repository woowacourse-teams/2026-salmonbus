package com.gustler.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class Sal134MigrationTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18");

    private Sal134Migration.Settings settings;

    @BeforeEach
    void 기존_운영_구조와_자료를_준비한다() throws SQLException {
        settings = new Sal134Migration.Settings(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword(), POSTGRES.getDatabaseName());
        Flyway.configure().dataSource(settings.url(), settings.user(), settings.password())
                .cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(settings.url(), settings.user(), settings.password())
                .target("16").load().migrate();
        insertLegacyData();
    }

    @Test
    void 중단한_묶음_다음부터_재개하고_최종_변경을_미리_검증한다() throws Exception {
        run("prepare");
        Sal134Migration.run(new Sal134Migration.Options("backfill", Path.of("unused"), true, "backup-after-stop", 1, 2), settings);
        assertThat(number("SELECT count(*) FROM sal134_transition_progress")).isEqualTo(1);
        assertThat(number("SELECT processed_rows FROM sal134_transition_progress WHERE step = 'route-quality'")).isOne();

        run("backfill");
        run("prepare");
        run("verify");

        assertThat(number("SELECT count(*) FROM sal134_transition WHERE verified_at IS NOT NULL")).isOne();
        assertThat(number("SELECT count(*) FROM information_schema.columns WHERE table_name = 'seat_forecast' AND column_name = 'scoring_state'")).isOne();
        assertThat(number("SELECT count(*) FROM observation_batch WHERE input_confirmed_at IS NOT NULL")).isEqualTo(3);
        assertThat(number("SELECT count(*) FROM forecast_publication WHERE provenance = 'LEGACY_UNKNOWN' AND model_deployment_id IS NULL")).isOne();
        assertThat(number("SELECT count(*) FROM observation_trip_assignment")).isZero();

        run("finalize");
        run("finalize");
        assertThat(number("SELECT count(*) FROM information_schema.columns WHERE table_name = 'seat_forecast' AND column_name = 'scoring_state'")).isZero();
        assertThat(number("SELECT count(*) FROM quality_training_seat_forecast")).isOne();
        assertThat(number("SELECT max(version::integer) FROM flyway_schema_history")).isEqualTo(18);
    }

    @Test
    void 한_묶음의_복사가_실패하면_대상과_진행_위치를_함께_롤백한다() throws Exception {
        run("prepare");
        execute("""
            CREATE FUNCTION fail_evaluation_copy() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN RAISE EXCEPTION 'test chunk failure'; END $$;
            CREATE TRIGGER fail_evaluation BEFORE INSERT ON forecast_evaluation
            FOR EACH ROW EXECUTE FUNCTION fail_evaluation_copy();
            """);

        assertThatThrownBy(() -> run("backfill")).hasMessageContaining("test chunk failure");
        assertThat(number("SELECT count(*) FROM forecast_evaluation")).isZero();
        assertThat(number("SELECT count(*) FROM sal134_transition_progress WHERE step = 'evaluation'")).isZero();

        execute("DROP TRIGGER fail_evaluation ON forecast_evaluation; DROP FUNCTION fail_evaluation_copy()");
        run("backfill");
        run("verify");
        assertThat(number("SELECT count(*) FROM forecast_evaluation")).isOne();
    }

    @Test
    void 원본_건수가_같아도_값이_바뀌면_검증을_거절한다() throws Exception {
        run("prepare");
        run("backfill");
        execute("UPDATE vehicle_observation SET remaining_seats = 13 WHERE id = 1");

        assertThatThrownBy(() -> run("verify")).hasMessageContaining("vehicle_observation");
        assertThat(number("SELECT count(*) FROM sal134_transition WHERE verified_at IS NOT NULL")).isZero();
        assertThatThrownBy(() -> run("prepare")).hasMessageContaining("vehicle_observation");
    }

    @Test
    void 대상_평가의_값이_다르면_검증_완료를_남기지_않는다() throws Exception {
        run("prepare");
        run("backfill");
        execute("UPDATE forecast_evaluation SET seats_on_arrival = 8");

        assertThatThrownBy(() -> run("verify")).hasMessageContaining("전환 자료 대조 실패");
        assertThat(number("SELECT count(*) FROM sal134_transition WHERE verified_at IS NOT NULL")).isZero();
    }

    @Test
    void 검증_이후_진행_기록이_변경되면_최종_전환을_거절한다() throws Exception {
        run("prepare");
        run("backfill");
        run("verify");

        execute("UPDATE sal134_transition_progress SET completed = false WHERE step = 'evaluation'");

        assertThat(number("SELECT count(*) FROM sal134_transition WHERE verified_at IS NOT NULL")).isZero();
        assertThatThrownBy(() -> Flyway.configure().dataSource(settings.url(), settings.user(), settings.password())
                .target("18").load().migrate()).hasMessageContaining("backfill 검증");
        assertThat(number("SELECT count(*) FROM information_schema.columns WHERE table_name = 'seat_forecast' AND column_name = 'scoring_state'")).isOne();
    }

    @Test
    void 배치_안에_생성_시각이_다르면_발행을_추정하지_않는다() throws Exception {
        execute("""
            INSERT INTO route_stop(route_version_id,stop_order,stop_id,name,direction,boarding_allowed)
            VALUES(1,3,'stop3','세 번째','UP',true);
            INSERT INTO seat_forecast(vehicle_observation_id,target_stop_order,route_version_id,stops_to_target,
                model_deployment_id,demand_statistics_revision,seat_full_chance_raw,seat_full_chance,generated_at,
                scoring_state,quality_revision)
            VALUES(1,3,1,2,1,1,0.2,0.3,'2026-09-01T00:00:02Z','PENDING',7)
            """);
        run("prepare");

        assertThatThrownBy(() -> run("backfill")).hasMessageContaining("기존 자료가 전환 조건을 만족하지 않습니다");
        assertThat(number("SELECT count(*) FROM forecast_publication")).isZero();
    }

    @Test
    void 중지_확인과_동일한_백업_식별이_없으면_변경하지_않는다() throws Exception {
        assertThatThrownBy(() -> Sal134Migration.run(new Sal134Migration.Options("prepare", Path.of("unused"),
                false, "backup-after-stop", 1000, 0), settings)).hasMessageContaining("writers-stopped");
        assertThat(number("SELECT max(version::integer) FROM flyway_schema_history")).isEqualTo(16);
        run("prepare");

        assertThatThrownBy(() -> Sal134Migration.run(new Sal134Migration.Options("backfill", Path.of("unused"),
                true, "another-backup", 1000, 0), settings)).hasMessageContaining("백업 식별과 다릅니다");
    }

    @Test
    void 예상한_DB_이름이_다르면_전환_준비를_시작하지_않는다() throws Exception {
        final Sal134Migration.Settings wrongTarget = new Sal134Migration.Settings(settings.url(), settings.user(),
                settings.password(), "a-different-database");

        assertThatThrownBy(() -> Sal134Migration.run(new Sal134Migration.Options("prepare", Path.of("unused"),
                true, "backup-after-stop", 1000, 0), wrongTarget)).hasMessageContaining("DB 이름이 다릅니다");
        assertThat(number("SELECT max(version::integer) FROM flyway_schema_history")).isEqualTo(16);
    }

    @Test
    void 기존_학습_제외_기록과_조회_결과를_보존한다() throws Exception {
        Flyway.configure().dataSource(settings.url(), settings.user(), settings.password())
                .target("17").load().migrate();
        // historical 전체 이관 도구를 테스트 의존성으로 다시 포함하지 않는다.
        // 기존 장부의 컬럼·값과 동일한 최소 fixture를 사용한다.
        execute("""
            CREATE TABLE training_model_release_exclusion (LIKE model_training_exclusion INCLUDING ALL);
            CREATE TABLE training_statistics_generation_exclusion (LIKE statistics_training_exclusion INCLUDING ALL);
            INSERT INTO training_model_release_exclusion
            VALUES('release1',repeat('1',64),1,'calc1','2026-08-02T00:00:00Z',NULL,0,
                '2026-08-02T00:00:00Z','TEMPORARY_RELEASE','test exclusion','2026-08-02T00:00:00Z');
            INSERT INTO training_statistics_generation_exclusion
            VALUES('00000000-0000-0000-0000-000000000001','release1',repeat('1',64),1,'calc1',1,
                '2026-08-31T00:00:00Z','2026-08-31T01:00:00Z',1,'2026-08-31T02:00:00Z');
            SELECT refresh_trip_quality_training_views();
            """);
        run("prepare");
        run("backfill");
        run("verify");
        run("finalize");

        assertThat(number("SELECT count(*) FROM model_training_exclusion")).isOne();
        assertThat(number("SELECT count(*) FROM statistics_training_exclusion")).isOne();
        assertThat(number("SELECT count(*) FROM training_model_release_exclusion")).isOne();
        assertThat(number("SELECT count(*) FROM training_eligible_seat_forecast")).isZero();
        assertThat(number("SELECT count(*) FROM training_eligible_stop_demand_statistics")).isZero();
    }

    @Test
    void 제외된_편도의_평가를_옮겨도_학습_조회에_다시_포함하지_않는다() throws Exception {
        execute("""
            INSERT INTO vehicle_one_way_trip(id,start_observation_id,route_version_id,vehicle_id,status,boundary,rule_version)
            VALUES('excluded-trip',1,1,'bus1','EXCLUDED','CONFIRMED','quality1');
            UPDATE vehicle_observation SET vehicle_trip_key = 'excluded-trip';
            """);
        run("prepare");
        run("backfill");
        run("finalize");

        assertThat(number("SELECT count(*) FROM observation_trip_assignment")).isEqualTo(2);
        assertThat(number("SELECT count(*) FROM forecast_evaluation WHERE scoring_state = 'SETTLED'")).isOne();
        assertThat(number("SELECT count(*) FROM quality_training_seat_forecast")).isZero();
        assertThat(number("SELECT count(*) FROM training_eligible_seat_forecast")).isZero();
    }

    @Test
    void 품질_판정과_조사가_실제로_참조한_배치만_입력을_확정한다() throws Exception {
        execute("""
            INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,response_received_at,
                outcome,normalization_version,collection_strategy_version)
            SELECT 1, '2026-09-01T00:00:00Z'::timestamptz + number * interval '1 minute', 1,
                'quality-' || number, '2026-09-01T00:00:00Z'::timestamptz + number * interval '1 minute',
                'SUCCESS_ROWS','n1','c1'
            FROM generate_series(4,10) number;
            INSERT INTO vehicle_observation(observation_batch_id,route_version_id,source_row_number,vehicle_id,
                stop_order,stop_id,running_state,remaining_seats,passed_stop_order)
            SELECT id,1,0,'bus2',2,'stop2',2,10,2 FROM observation_batch WHERE id BETWEEN 4 AND 10 ORDER BY id;
            INSERT INTO vehicle_one_way_trip(id,start_observation_id,route_version_id,vehicle_id,status,boundary,
                rule_version,evidence_observation_id,assessed_at)
            VALUES('quality-reference',3,1,'bus2','ELIGIBLE','CONFIRMED','quality1',4,'2026-09-01T00:20:00Z'),
                ('earlier-reference',3,1,'bus2','ELIGIBLE','CONFIRMED','quality1',NULL,'2026-09-01T00:15:00Z');
            INSERT INTO trip_quality_rebuild(route_version_id,vehicle_id,until_at,anchor_observation_id,
                previous_observation_id,boundary_candidate_observation_id,evidence_observation_id,investigated_at)
            VALUES(1,'bus2','2026-09-01T01:00:00Z',5,6,7,8,'2026-09-01T00:30:00Z');
            """);
        run("prepare");
        run("backfill");
        run("finalize");

        assertThat(number("SELECT count(*) FROM observation_batch WHERE id = 4 AND input_confirmed_at = '2026-09-01T00:15:00Z'")).isOne();
        assertThat(number("SELECT count(*) FROM observation_batch WHERE id = 5 AND input_confirmed_at = '2026-09-01T00:20:00Z'")).isOne();
        assertThat(number("SELECT count(*) FROM observation_batch WHERE id BETWEEN 6 AND 9 AND input_confirmed_at = '2026-09-01T00:30:00Z'")).isEqualTo(4);
        assertThat(number("SELECT count(*) FROM observation_batch WHERE id = 10 AND input_confirmed_at IS NULL")).isOne();
        assertThat(number("SELECT count(*) FROM vehicle_observation")).isEqualTo(9);
    }

    @Test
    void 품질_조사가_없는_관측을_참조하면_ID를_알리고_전환을_중단한다() throws Exception {
        execute("""
            INSERT INTO trip_quality_rebuild(route_version_id,vehicle_id,until_at,anchor_observation_id)
            VALUES(1,'bus2','2026-09-01T01:00:00Z',999999)
            """);
        run("prepare");

        assertThatThrownBy(() -> run("backfill")).hasMessageContaining("validate-legacy.sql 검사 7")
                .hasMessageContaining("999999");
        assertThat(number("SELECT count(*) FROM sal134_transition_progress")).isZero();
        assertThat(number("SELECT count(*) FROM observation_batch WHERE input_confirmed_at IS NOT NULL")).isZero();
    }

    @Test
    void 빈_DB에서도_전환_명령을_끝까지_실행할_수_있다() throws Exception {
        Flyway.configure().dataSource(settings.url(), settings.user(), settings.password())
                .cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(settings.url(), settings.user(), settings.password())
                .target("16").load().migrate();

        run("prepare");
        run("backfill");
        run("verify");
        run("finalize");
        assertThat(number("SELECT count(*) FROM forecast_publication")).isZero();
    }

    private void run(final String command) throws Exception {
        Sal134Migration.run(new Sal134Migration.Options(command, Path.of("unused"), true,
                "backup-after-stop", 1000, 0), settings);
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

    private void execute(final String sql) throws SQLException {
        try (Connection connection = settings.connect(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long number(final String sql) throws SQLException {
        try (Connection connection = settings.connect(); var statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
