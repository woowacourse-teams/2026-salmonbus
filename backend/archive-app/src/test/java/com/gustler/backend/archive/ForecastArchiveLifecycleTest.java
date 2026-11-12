package com.gustler.backend.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.gustler.backend.forecasting.application.publication.ForecastBatchCommitter;
import com.gustler.backend.forecasting.application.publication.ForecastBatchWriter;
import com.gustler.backend.forecasting.domain.deployment.ActiveModelDeployment;
import com.gustler.backend.forecasting.domain.deployment.RuntimeSnapshot;
import com.gustler.backend.forecasting.domain.deployment.SupportedForecastScope;
import com.gustler.backend.forecasting.domain.model.RouteDirection;
import com.gustler.backend.forecasting.domain.model.RouteStop;
import com.gustler.backend.forecasting.domain.model.RouteStops;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareSeatForecastModel;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareSeatPredictor;
import com.gustler.backend.forecasting.domain.model.alighting.NumericTreeEnsemble;
import com.gustler.backend.forecasting.domain.publication.ForecastPublication;
import com.gustler.backend.forecasting.domain.publication.PendingForecastBatch;
import com.gustler.backend.forecasting.infrastructure.alighting.ConfirmedAlightingAwareInput;
import com.gustler.backend.forecasting.infrastructure.alighting.JdbcAlightingAwareFeatureQuery;
import com.gustler.backend.forecasting.infrastructure.capacity.JdbcVehicleCapacityStore;
import com.gustler.backend.forecasting.infrastructure.capacity.VehicleCapacityCache;
import com.gustler.backend.forecasting.infrastructure.jdbc.JdbcAlightingAwareForecastEvaluation;
import com.gustler.backend.forecasting.infrastructure.jdbc.JdbcForecastPublicationRepository;
import com.gustler.backend.forecasting.infrastructure.jdbc.JdbcRouteWorkAccess;
import com.gustler.backend.observations.api.CollectionInputs;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 합성 관측의 실제 PG 흐름을 검증한다. 예측 정확도나 운영 처리량 실험이 아니다. */
class ForecastArchiveLifecycleTest {
    @TempDir Path directory;
    private static final Instant SOURCE=Instant.parse("2026-11-09T00:00:00Z");

    @Test
    void 과거_원본을_삭제해도_예보의_정원으로_정산하고_보관기간_후_전체를_이관한다() {
        var fixture=new OperationalArchiveIntegrationTest();
        fixture.migrate();
        try {
            fixture.setup();
            var jdbc=fixture.jdbc;
            long version=fixture.version;
            jdbc.sql("UPDATE route SET source_route_id='204000057'").update();
            jdbc.sql("INSERT INTO route_stop(route_version_id,stop_order,stop_id,name,direction,boarding_allowed) VALUES(?,3,'s3','S3','UP',true),(?,4,'s4','S4','UP',false)")
                .params(version,version).update();
            var source=new DriverManagerDataSource(fixture.database.jdbcUrl(),fixture.database.username(),fixture.database.password());
            var manager=new JdbcTransactionManager(source);
            var tx=new TransactionTemplate(manager);
            var routeWork=new JdbcRouteWorkAccess(jdbc);
            long oldBatch=fixture.batch("2026-11-06T00:00Z");
            long witness=fixture.observation(oldBatch);
            jdbc.sql("UPDATE observation_batch SET ingestion_origin='LIVE',stored_rows=1,input_confirmed_at=response_received_at WHERE id=?")
                .param(oldBatch).update();
            jdbc.sql("UPDATE vehicle_observation SET remaining_seats=44 WHERE id=?").param(witness).update();
            jdbc.sql("INSERT INTO vehicle_capacity(source_id,vehicle_id,capacity,evidence_at,evidence_observation_id) VALUES('GBIS','vehicle',44,'2026-11-06T00:00Z',?)")
                .param(witness).update();
            pass(fixture,"previous",-120,1,5);
            pass(fixture,"previous",-100,2,0);
            pass(fixture,"previous",-80,3,0);
            long current=pass(fixture,"vehicle",0,1,40);
            long batch=jdbc.sql("SELECT observation_batch_id FROM vehicle_observation WHERE id=?")
                .param(current).query(Long.class).single();
            long route=jdbc.sql("SELECT route_id FROM route_version WHERE id=?").param(version).query(Long.class).single();
            long deployment=jdbc.sql("""
                INSERT INTO model_deployment(deployment_key,release_id,model_key,model_version,bundle_digest,
                    prediction_target_version,calculation_version,supported_scope_digest,data_until,state)
                VALUES(?,'lifecycle','alighting-gate-base-and-pool','v1',repeat('a',64),'after-alighting-and-departure-zero-v1',
                    'alighting-online-capacity-snapshot-v2',repeat('b',64),'2026-10-21','ACTIVE') RETURNING id
                """).param(UUID.randomUUID()).query(Long.class).single();
            var stops=new RouteStops(version,"204000057",List.of(new RouteStop(version,1,"s1",true,RouteDirection.UP),
                new RouteStop(version,2,"s2",true,RouteDirection.UP),new RouteStop(version,3,"s3",true,RouteDirection.UP),new RouteStop(version,4,"s4",false,RouteDirection.UP)),"3330");
            var statistics=statistics();
            var cache=new VehicleCapacityCache(new JdbcVehicleCapacityStore(jdbc));
            var query=new JdbcAlightingAwareFeatureQuery(jdbc,cache,version+":"+oldBatch,1000,false,directory.toString());
            var publications=new JdbcForecastPublicationRepository(jdbc,new JdbcTemplate(source));
            // 실제 서비스 프록시와 같은 발행 트랜잭션 경계를 사용한다.
            var committer=new ForecastBatchCommitter(publications,routeWork,mock(CollectionInputs.class)) {
                @Override public int commitConfirmed(PendingForecastBatch pending,ForecastPublication publication) {
                    return tx.execute(status -> super.commitConfirmed(pending,publication));
                }
            };
            var model=new AlightingAwareSeatForecastModel(new AlightingAwareSeatPredictor(
                constant(88,NumericTreeEnsemble.Kind.REGRESSION,10),
                constant(88,NumericTreeEnsemble.Kind.MULTICLASS,0,0,0),
                constant(80,NumericTreeEnsemble.Kind.BINARY,0),1.3),
                Map.of("3330",statistics),Map.of("3330",Map.of(2,evidence(),3,evidence())),"a".repeat(64));
            var writer=new ForecastBatchWriter(committer,Clock.fixed(SOURCE.plusSeconds(1),ZoneOffset.UTC),routeWork);
            writer.setCompactSettlementWrite(true);
            writer.setAlightingAwareFeatures(query);
            writer.setConfirmedInput(new ConfirmedAlightingAwareInput(jdbc,manager));
            assertThat(writer.writeForecastsOf(new PendingForecastBatch(batch,version,route,SOURCE),stops,
                new RuntimeSnapshot(new ActiveModelDeployment(deployment,"alighting-online-capacity-snapshot-v2","lifecycle","a".repeat(64)),
                    new SupportedForecastScope(List.of("3330")),model,SOURCE.minusSeconds(86400)))).isEqualTo(2);
            double[] before=query.read(batch,stops,statistics).get(current).forTarget(2).seat88();

            jdbc.sql("UPDATE vehicle_capacity SET capacity=45,evidence_at=? WHERE vehicle_id='vehicle'")
                .param(offset(SOURCE.plusSeconds(10))).update();
            cache.warmUp();
            var objects=new OperationalArchiveIntegrationTest.MemoryObjects();
            fixture.service(objects).advance();
            assertThat(jdbc.sql("SELECT count(*) FROM vehicle_observation WHERE id=?").param(witness).query(Long.class).single()).isZero();
            assertThat(query.read(batch,stops,statistics).get(current).forTarget(2).seat88()).containsExactly(before);
            assertThat(jdbc.sql("SELECT (feature_snapshot->>'capacity')::int FROM seat_forecast WHERE vehicle_observation_id=? AND target_stop_order=2")
                .param(current).query(Integer.class).single()).isEqualTo(44);

            pass(fixture,"vehicle",20,1,2);
            pass(fixture,"vehicle",40,2,0);
            pass(fixture,"vehicle",60,2,0);
            pass(fixture,"vehicle",80,3,2);
            jdbc.sql("UPDATE alighting_forecast_evaluation_work SET next_attempt_at='-infinity'").update();
            new JdbcAlightingAwareForecastEvaluation(jdbc,routeWork,Clock.fixed(SOURCE.plusSeconds(90),ZoneOffset.UTC),manager).settle();
            assertThat(jdbc.sql("SELECT scoring_state FROM alighting_forecast_evaluation_result WHERE vehicle_observation_id=? AND target_stop_order=2")
                .param(current).query(String.class).single()).isEqualTo("SETTLED");
            assertThat(jdbc.sql("SELECT capacity FROM alighting_forecast_evaluation_result WHERE vehicle_observation_id=? AND target_stop_order=2")
                .param(current).query(Integer.class).single()).isEqualTo(44);

            jdbc.sql("UPDATE alighting_forecast_evaluation_work SET next_attempt_at='-infinity'").update();
            new JdbcAlightingAwareForecastEvaluation(jdbc,routeWork,Clock.fixed(SOURCE.plusSeconds(7202),ZoneOffset.UTC),manager).settle();
            assertThat(jdbc.sql("SELECT scoring_state FROM alighting_forecast_evaluation_result WHERE vehicle_observation_id=? AND target_stop_order=3")
                .param(current).query(String.class).single()).isEqualTo("LOST");
            assertThat(fixture.count("alighting_forecast_evaluation_work")).isZero();

            var archive = new ArchiveService(fixture.repository, objects,
                Clock.fixed(SOURCE.plusSeconds(3 * 86400), ZoneOffset.UTC),
                new SimpleMeterRegistry(), "salmonbus/test/lake-v2/", 2, true);
            archive.advance();
            assertThat(jdbc.sql("SELECT failure_code FROM operational_archive_job WHERE state<>'DONE'")
                .query(String.class).single()).isEqualTo("ALIGHTING_HISTORY_REQUIRED");

            long next = pass(fixture, "vehicle", 3 * 86400, 1, 40);
            long nextBatch = jdbc.sql("SELECT observation_batch_id FROM vehicle_observation WHERE id=?")
                .param(next).query(Long.class).single();
            assertThat(query.read(nextBatch, stops, statistics)).containsKey(next);
            jdbc.sql("UPDATE operational_archive_job SET retry_at='-infinity' WHERE state<>'DONE'").update();
            archive.advance();
            assertThat(jdbc.sql("SELECT id FROM vehicle_observation").query(Long.class).list()).containsExactly(next);
            assertThat(fixture.count("seat_forecast")).isZero();
            assertThat(objects.values.entrySet().stream()
                .filter(entry -> entry.getKey().contains("-dimensions-"))
                .flatMap(entry -> ArchiveFiles.decode(entry.getValue()).stream()))
                .anySatisfy(row -> assertThat(row).contains("\"table\": \"model_deployment\"", "\"settlement_context\"",
                    "\"realStopOrders\"", "\"stops\""));
            assertThat(fixture.count("alighting_forecast_evaluation_result")).isZero();
            assertThat(jdbc.sql("SELECT sum(row_count) FROM operational_archive_file WHERE table_name='alighting_forecast_evaluation_result'")
                .query(Long.class).single()).isEqualTo(2);
        } finally {
            fixture.close();
        }
    }

    private static long pass(OperationalArchiveIntegrationTest fixture,String vehicle,int seconds,int stop,int seats) {
        long batch=fixture.batch(offset(SOURCE.plusSeconds(seconds)).toString());
        fixture.jdbc.sql("UPDATE observation_batch SET ingestion_origin='LIVE',stored_rows=1,input_confirmed_at=response_received_at+interval '1 second' WHERE id=?")
            .param(batch).update();
        return fixture.jdbc.sql("""
            INSERT INTO vehicle_observation(observation_batch_id,route_version_id,source_row_number,vehicle_id,stop_order,
                stop_id,running_state,passed_stop_order,remaining_seats)
            VALUES(?,?,1,?,?,?,2,?,?) RETURNING id
            """).params(batch,fixture.version,vehicle,stop,"s"+stop,stop,seats).query(Long.class).single();
    }

    private static AlightingAwareRouteStatistics statistics() {
        Map<String,Map<String,Double>> tables=new HashMap<>();
        for(String name:List.of("lam","lam_base","net_tab","full_tab","dep_tab","stop_net_base")) { tables.put(name,Map.of()); }
        return new AlightingAwareRouteStatistics("3330",List.of(
            new AlightingAwareRouteStatistics.Stop(1,"s1","S1",false,true),
            new AlightingAwareRouteStatistics.Stop(2,"s2","S2",false,true),
            new AlightingAwareRouteStatistics.Stop(3,"s3","S3",false,true),
            new AlightingAwareRouteStatistics.Stop(4,"s4","S4",false,false)),1,tables,Map.of(),Set.of(),true);
    }

    private static NumericTreeEnsemble constant(int size,NumericTreeEnsemble.Kind kind,double... baseline) {
        List<List<NumericTreeEnsemble.Node>> trees=new ArrayList<>();
        for(double ignored:baseline) { trees.add(List.of(new NumericTreeEnsemble.Node(0,0,false,0,0,0,true))); }
        return new NumericTreeEnsemble(size,kind,baseline,List.of(trees));
    }

    private static String evidence() {
        return """
            {"realStopOrders":[1,2,3],"turn":4,"holidays":[],"alighting":{"stops":[
                {"stop":2,"band":"morning","lam":[1,1,1,1,1,1,1,1],"p":0.5,"kappa":2,"r":3}]}}
            """;
    }

    private static OffsetDateTime offset(Instant at) { return at.atOffset(ZoneOffset.UTC); }
}
