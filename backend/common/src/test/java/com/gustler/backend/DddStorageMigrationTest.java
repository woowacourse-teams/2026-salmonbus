package com.gustler.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        migrate("18");

        assertThat(number("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '" + schema
            + "' AND table_name IN ('forecast_publication','forecast_evaluation','demand_statistics_version',"
            + "'route_data_quality','route_version_quality_policy','observation_trip_assignment')")).isEqualTo(6);
        assertThat(number("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = '" + schema
            + "' AND table_name = 'seat_forecast' AND column_name = 'scoring_state'")).isZero();
        assertThat(number("SELECT COUNT(*) FROM model_active_slot WHERE model_deployment_id IS NULL AND version = 0")).isOne();
    }

    @Test
    void 검증하지_않은_기존_DB에서는_컬럼을_삭제하기_전에_전환을_거절한다() throws SQLException {
        migrate("16");
        insertRoute();
        migrate("17");

        assertThatThrownBy(() -> migrate("18")).hasMessageContaining("backfill 검증");
        assertThat(number("SELECT quality_revision FROM route WHERE id = 1")).isEqualTo(7);
        assertThat(number("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = '" + schema
            + "' AND table_name = 'seat_forecast' AND column_name = 'scoring_state'")).isOne();
    }

    @Test
    void 검증_이후_변경이_발생하면_검증_완료를_취소한다() throws SQLException {
        migrate("16");
        insertRoute();
        migrate("17");
        execute("INSERT INTO sal134_transition(backup_id, verified_at) VALUES ('stopped-writer-backup', now())");

        execute("UPDATE route SET display_name = '변경된 노선' WHERE id = 1");

        assertThat(number("SELECT COUNT(*) FROM sal134_transition WHERE verified_at IS NOT NULL")).isZero();
        assertThatThrownBy(() -> migrate("18")).hasMessageContaining("backfill 검증");
    }

    @Test
    void 예측_평가와_품질을_분리해도_원관측과_학습_조회_값을_보존한다() throws SQLException {
        migrate("16");
        insertLegacyData();
        String observationBefore = scalar("SELECT string_agg(row_to_json(o)::text, ',' ORDER BY id) FROM vehicle_observation o");
        String viewColumnsBefore = viewColumns();
        String forecastsBefore = scalar("SELECT string_agg(row_to_json(f)::text, ',' ORDER BY vehicle_observation_id,target_stop_order) FROM quality_training_seat_forecast f");
        migrate("17");
        copyLegacyData();
        execute("INSERT INTO sal134_transition(backup_id, verified_at) VALUES ('stopped-writer-backup', now())");

        migrate("18");

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
        migrate("16");
        insertLegacyData();
        migrate("17");
        copyLegacyData();
        execute("INSERT INTO sal134_transition(backup_id, verified_at) VALUES ('stopped-writer-backup', now())");
        migrate("18");

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
        migrate("16");
        insertLegacyData();
        migrate("17");

        execute("""
            INSERT INTO forecast_publication(source_batch_id, source_attempt_number, route_version_id,
                observed_at, published_at, prediction_count, provenance)
            VALUES (3, 1, 1, '2026-09-01T00:02:00Z', '2026-09-01T00:02:01Z', 0, 'LEGACY_UNKNOWN')
            """);
        assertThat(number("SELECT COUNT(*) FROM forecast_publication WHERE model_deployment_id IS NULL")).isOne();
        assertThatThrownBy(() -> execute("""
            INSERT INTO forecast_publication(source_batch_id, source_attempt_number, route_version_id,
                observed_at, published_at, prediction_count, provenance)
            VALUES (2, 1, 1, '2026-09-01T00:01:00Z', '2026-09-01T00:01:01Z', 0, 'RECORDED')
            """)).isInstanceOf(SQLException.class);
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
            INSERT INTO forecast_publication(source_batch_id,source_attempt_number,route_version_id,model_deployment_id,
                demand_statistics_revision,quality_revision,observed_at,generated_at,published_at,prediction_count)
            VALUES(1,1,1,1,1,7,'2026-09-01T00:00:00Z','2026-09-01T00:00:01Z','2026-09-01T00:00:01Z',1);
            INSERT INTO forecast_publication(source_batch_id,source_attempt_number,route_version_id,observed_at,published_at,prediction_count,provenance)
            VALUES(3,1,1,'2026-09-01T00:02:00Z','2026-09-01T00:02:01Z',0,'LEGACY_UNKNOWN');
            UPDATE seat_forecast SET publication_id = 1;
            INSERT INTO forecast_evaluation(vehicle_observation_id,target_stop_order,route_version_id,scoring_state,
                arrival_observation_id,seats_on_arrival,scored_at,arrived_at,arrival_route_version_id,arrival_vehicle_id,
                arrival_stop_order,arrival_running_state,arrival_remaining_seats,arrival_vehicle_trip_key,arrival_quality_direction)
            VALUES(1,2,1,'SETTLED',2,9,'2026-09-01T00:01:30Z','2026-09-01T00:01:00Z',1,'bus1',2,2,9,'legacy-unassigned',0);
            UPDATE observation_batch SET input_confirmed_at = COALESCE(forecast_completed_at,'2026-09-01T00:01:30Z');
            UPDATE model_active_slot SET model_deployment_id=1,version=1 WHERE id=1
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
