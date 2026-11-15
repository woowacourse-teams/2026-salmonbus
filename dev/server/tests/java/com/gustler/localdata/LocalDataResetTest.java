package com.gustler.localdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.support.TestDatabase;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/** 로컬 실행 이력 초기화가 실제 스키마에서 무엇을 비우고 무엇을 남기는지 본다. */
class LocalDataResetTest {
    private static final List<String> GENERATED_TABLES = List.of("daily_call_quota", "observation_batch",
        "vehicle_observation", "forecast_run", "forecast_record", "stop_pass", "pass_label", "forecast_unresolved",
        "daily_export");

    @Test
    void 초기화는_수집과_예보가_만든_줄과_내보내기_장부를_비우고_노선과_모델_배포는_남긴다() throws Exception {
        try (TestDatabase database = TestDatabase.create(); Connection connection = database.connection()) {
            // given
            Flyway.configure().dataSource(database.jdbcUrl(), database.username(), database.password()).load()
                .migrate();
            RouteDataset.Route route = RouteDataset.read(Path.of(System.getProperty("dev.data.directory"),
                "routes.json")).routes().getFirst();
            final long versionId = LocalData.seedRoute(connection, route);
            final long routeId = number(connection, "SELECT route_id FROM route_version WHERE id=?", versionId);
            final long deploymentId = insertModelDeployment(connection);
            final long batchId = insertBatch(connection, versionId);
            insertForecast(connection, routeId, versionId, deploymentId, batchId);
            insertScoring(connection, routeId, versionId, deploymentId, batchId);
            execute(connection, """
                INSERT INTO daily_export(table_name,source_date,object_key,row_count,sha256,exported_at)
                VALUES('forecast_run',current_date-5,'reset-test',1,repeat('a',64),now())
                """);

            // when
            LocalData.resetGenerated(connection);

            // then
            Map<String, Long> remaining = new LinkedHashMap<>();
            for (String table : GENERATED_TABLES) {
                remaining.put(table, number(connection, "SELECT count(*) FROM " + table));
            }
            final long routes = number(connection, "SELECT count(*) FROM route");
            final long stops = number(connection, "SELECT count(*) FROM route_stop");
            final long deployments = number(connection, "SELECT count(*) FROM model_deployment");
            assertSoftly(softly -> {
                softly.assertThat(remaining).allSatisfy((table, rows) -> assertThat(rows).as(table).isZero());
                softly.assertThat(routes).isEqualTo(1);
                softly.assertThat(stops).isEqualTo(route.stops().size());
                softly.assertThat(deployments).isEqualTo(1);
            });
        }
    }

    private static long insertModelDeployment(Connection connection) throws Exception {
        return number(connection, """
            INSERT INTO model_deployment(deployment_key,release_id,model_key,model_version,bundle_digest,
                prediction_target_version,calculation_version,supported_scope_digest,data_until,state)
            VALUES(gen_random_uuid(),'reset-test','seat-full-chance','1',repeat('1',64),'SEAT_FULL_CHANCE_V1',
                'CALCULATION_V1',repeat('0',64),now(),'ACTIVE')
            RETURNING id
            """);
    }

    private static long insertBatch(Connection connection, final long versionId) throws Exception {
        return number(connection, """
            INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,requested_at,
                response_received_at,outcome,stored_rows,normalization_version,collection_strategy_version)
            VALUES(?,now(),1,'reset-test',now(),now(),'SUCCESS_EMPTY',0,'test','test')
            RETURNING id
            """, versionId);
    }

    private static void insertForecast(
        Connection connection,
        final long routeId,
        final long versionId,
        final long deploymentId,
        final long batchId
    ) throws Exception {
        execute(connection, """
            INSERT INTO forecast_run(source_date,observation_batch_id,route_id,route_version_id,received_at,
                confirmed_at,model_deployment_id,outcome,prediction_count,source_incomplete,counted_rows)
            VALUES(current_date,?,?,?,now(),now(),?,'PUBLISHED',1,false,0)
            """, batchId, routeId, versionId, deploymentId);
        execute(connection, """
            INSERT INTO forecast_record(source_date,vehicle_observation_id,observation_batch_id,source_row_number,
                route_id,route_version_id,vehicle_id,passed_stop_order,trip_start_observation_id,
                chain_start_observation_id,down,capacity,extended,target_stop_orders,expected_seats,
                full_probabilities)
            VALUES(current_date,1,?,1,?,?,'reset-test',1,1,1,false,44,false,ARRAY[2],ARRAY[3.5],ARRAY[0.5])
            """, batchId, routeId, versionId);
    }

    private static void insertScoring(
        Connection connection,
        final long routeId,
        final long versionId,
        final long deploymentId,
        final long batchId
    ) throws Exception {
        execute(connection, """
            INSERT INTO stop_pass(source_date,route_id,trip_start_observation_id,stop_order,route_version_id,
                vehicle_id,chain_start_observation_id,departure_date,arrival_observation_id,arrival_seats,
                arrival_confirmed_at,arrival_received_at,first_observation_id,first_confirmed_at,
                last_observation_id,last_seats,last_confirmed_at,last_received_at,closure_observation_id,
                closure_batch_id,closed_at)
            VALUES(current_date,?,1,2,?,'reset-test',1,current_date,1,30,now(),now(),1,now(),1,30,now(),now(),1,?,
                now())
            """, routeId, versionId, batchId);
        execute(connection, """
            INSERT INTO pass_label(source_date,route_id,trip_start_observation_id,stop_order,capacity,
                label_version,model_deployment_id,state,label_value,computed_at)
            VALUES(current_date,?,1,2,44,'reset-test',?,'SETTLED',1.0,now())
            """, routeId, deploymentId);
        execute(connection, """
            INSERT INTO forecast_unresolved(source_date,vehicle_observation_id,target_stop_order,route_id,state,
                reason,closed_at)
            VALUES(current_date,1,3,?,'LOST','reset-test',now())
            """, routeId);
    }

    private static void execute(Connection connection, String sql, Object... arguments) throws Exception {
        try (PreparedStatement statement = prepare(connection, sql, arguments)) {
            statement.execute();
        }
    }

    private static long number(Connection connection, String sql, Object... arguments) throws Exception {
        try (PreparedStatement statement = prepare(connection, sql, arguments);
             ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static PreparedStatement prepare(Connection connection, String sql, Object... arguments)
        throws Exception {
        PreparedStatement statement = connection.prepareStatement(sql);
        for (int index = 0; index < arguments.length; index++) {
            statement.setObject(index + 1, arguments[index]);
        }
        return statement;
    }
}
