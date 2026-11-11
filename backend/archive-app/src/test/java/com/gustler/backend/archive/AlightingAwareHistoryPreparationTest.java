package com.gustler.backend.archive;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.forecasting.infrastructure.alighting.AlightingAwareHistoryBootstrap;
import com.gustler.backend.forecasting.infrastructure.alighting.AlightingAwareHistorySeedReader;
import com.gustler.backend.forecasting.domain.model.RouteDirection;
import com.gustler.backend.forecasting.domain.model.RouteStop;
import com.gustler.backend.forecasting.domain.model.RouteStops;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import com.gustler.backend.support.TestDatabase;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AlightingAwareHistoryPreparationTest {
    @TempDir
    Path directory;

    @Test
    void 운영_V60에서_확정_관측_이력을_준비하고_중복_실행해도_유지한다() {
        try (TestDatabase db = TestDatabase.create()) {
            DriverManagerDataSource source = new DriverManagerDataSource(db.jdbcUrl(), db.username(), db.password());
            Flyway flyway = Flyway.configure().dataSource(source).target("60").load();
            flyway.migrate();
            JdbcClient jdbc = JdbcClient.create(source);
            final long route = jdbc.sql("""
                INSERT INTO route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name)
                VALUES('r','GBIS','r','r','A','B') RETURNING id
                """).query(Long.class).single();
            final long version = jdbc.sql("""
                INSERT INTO route_version(route_id,content_digest,valid_from)
                VALUES(?,repeat('a',64),now()) RETURNING id
                """).param(route).query(Long.class).single();
            jdbc.sql("""
                INSERT INTO route_stop(route_version_id,stop_order,stop_id,name,direction,boarding_allowed)
                VALUES(?,1,'s1','A','UP',true)
                """).param(version).update();
            final long batch = jdbc.sql("""
                INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,
                    response_received_at,input_confirmed_at,ingestion_origin,outcome,stored_rows,
                    normalization_version,collection_strategy_version)
                VALUES(?,now(),1,'history',now(),now(),'LIVE','SUCCESS_ROWS',1,'n','c') RETURNING id
                """).param(version).query(Long.class).single();
            final long observation = jdbc.sql("""
                INSERT INTO vehicle_observation(observation_batch_id,route_version_id,source_row_number,
                    vehicle_id,stop_order,stop_id,passed_stop_order,running_state,remaining_seats)
                VALUES(?,?,0,'bus',1,'s1',1,2,45) RETURNING id
                """).params(batch, version).query(Long.class).single();
            RouteStops stops = new RouteStops(version, "r",
                List.of(new RouteStop(version, 1, "s1", true, RouteDirection.UP)), "r");
            Map<String, Map<String, Double>> tables = List.of(
                "lam", "lam_base", "net_tab", "full_tab", "dep_tab", "stop_net_base").stream()
                .collect(Collectors.toMap(name -> name, name -> Map.of()));
            AlightingAwareRouteStatistics statistics = new AlightingAwareRouteStatistics("r",
                List.of(new AlightingAwareRouteStatistics.Stop(1, "s1", "A", false, true)),
                2, tables, Map.of(), Set.of(), true);
            AlightingAwareHistoryBootstrap bootstrap = new AlightingAwareHistoryBootstrap(jdbc);

            final int checkpoints = bootstrap.rebuild(stops, statistics, batch, batch, 1000, 100, directory.toString());
            var seed = new AlightingAwareHistorySeedReader(
                directory.resolve(AlightingAwareRouteStatistics.CONFIRMED_PROFILE).toString(), 100).read(version);

            assertThat(checkpoints).isOne();
            assertThat(seed.batchId()).isEqualTo(batch);
            assertThat(seed.observations()).extracting(item -> item.observationId()).containsExactly(observation);
            assertThat(bootstrap.rebuild(stops, statistics, batch, batch, 1000, 100, directory.toString())).isZero();
            assertThat(jdbc.sql("SELECT to_regclass('vehicle_capacity') IS NULL").query(Boolean.class).single()).isTrue();
            assertThat(jdbc.sql("SELECT count(*) FROM vehicle_observation").query(Long.class).single()).isOne();
            flyway.validate();
        }
    }
}
