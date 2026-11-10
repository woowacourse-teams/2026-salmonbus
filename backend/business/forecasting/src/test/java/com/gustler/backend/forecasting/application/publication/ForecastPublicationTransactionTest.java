package com.gustler.backend.forecasting.application.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;

import com.gustler.backend.forecasting.application.RouteWorkAccess;
import com.gustler.backend.forecasting.domain.model.ObservedVehicle;
import com.gustler.backend.forecasting.domain.model.RouteStop;
import com.gustler.backend.forecasting.domain.model.SeatForecastResult;
import com.gustler.backend.forecasting.domain.model.VehicleStopTarget;
import com.gustler.backend.forecasting.domain.publication.ForecastPublication;
import com.gustler.backend.forecasting.domain.publication.ForecastPublicationRepository;
import com.gustler.backend.forecasting.domain.publication.PendingForecastBatch;
import com.gustler.backend.forecasting.domain.publication.SeatForecast;
import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import com.gustler.backend.observations.api.CollectionInputs;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

@ForecastingIntegrationTest
class ForecastPublicationTransactionTest {
    private static final Instant OBSERVED_AT = Instant.parse("2026-09-21T00:00:00Z");
    private static final Instant GENERATED_AT = OBSERVED_AT.plusSeconds(2);
    private static final String DIGEST = "0".repeat(64);
    private static final int ATTEMPT_NUMBER = 2;
    @Autowired JdbcClient jdbc;
    @Autowired ForecastBatchCommitter committer;
    @Autowired ForecastPublicationRepository publications;
    @Autowired RouteWorkAccess routeWork;
    @Autowired TransactionTemplate tx;
    private long routeId, routeVersionId, batchId, observationId, deploymentId, nextDeploymentId;
    private PendingForecastBatch batch;

    @BeforeEach
    void 발행할_입력을_저장한다() {
        String routeKey = "publication-" + UUID.randomUUID().toString().substring(0, 12);
        routeId = jdbc.sql("""
            INSERT INTO route (
                public_route_id, source_id, source_route_id, display_name, start_stop_name, end_stop_name
            ) VALUES (?, 'GBIS', ?, '3330', '범계역', '강남역') RETURNING id
            """)
            .params(routeKey, routeKey).query(Long.class).single();
        routeVersionId = jdbc.sql("""
            INSERT INTO route_version (route_id, content_digest, valid_from)
            VALUES (?, ?, ?) RETURNING id
            """)
            .params(routeId, DIGEST, offset(OBSERVED_AT)).query(Long.class).single();
        for (int order = 3; order <= 5; order++) {
            jdbc.sql("""
                INSERT INTO route_stop (route_version_id, stop_order, stop_id, name, direction, boarding_allowed)
                VALUES (?, ?, ?, ?, 'UP', true)
                """)
                .params(routeVersionId, order, "stop-" + order, "정류장 " + order).update();
        }
        batchId = jdbc.sql("""
            INSERT INTO observation_batch (
                route_version_id, scheduled_at, attempt_number, attempt_key, requested_at,
                response_received_at, completed_at, outcome, normalization_version, collection_strategy_version
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 'SUCCESS_ROWS', 'normalization-v1', 'collection-v1')
            RETURNING id
            """)
            .params(routeVersionId, offset(OBSERVED_AT), ATTEMPT_NUMBER, routeKey,
                offset(OBSERVED_AT), offset(OBSERVED_AT), offset(OBSERVED_AT))
            .query(Long.class).single();
        observationId = jdbc.sql("""
            INSERT INTO vehicle_observation (
                observation_batch_id, route_version_id, source_row_number, vehicle_id,
                stop_order, stop_id, passed_stop_order, running_state, remaining_seats
            ) VALUES (?, ?, 0, 'vehicle-1', 3, 'stop-3', 3, 2, 12) RETURNING id
            """)
            .params(batchId, routeVersionId).query(Long.class).single();
        batch = new PendingForecastBatch(batchId, routeVersionId, routeId, OBSERVED_AT);
        deploymentId = jdbc.sql("""
            INSERT INTO model_deployment(deployment_key,release_id,model_key,model_version,bundle_digest,
                prediction_target_version,calculation_version,supported_scope_digest,data_until,state)
            VALUES(?,'publication-test','alighting-gate-base-and-pool','v1',?,'after-alighting-and-departure-zero-v1',
                'alighting-online-capacity-snapshot-v2',?,?,'STAGED') RETURNING id
            """).params(UUID.randomUUID(),DIGEST,DIGEST,offset(OBSERVED_AT.minusSeconds(60))).query(Long.class).single();
    }

    @AfterEach
    void 자료를_정리한다() {
        for (String table : List.of("alighting_forecast_evaluation_work", "seat_forecast", "forecast_publication",
            "vehicle_observation", "observation_batch", "route_stop")) {
            jdbc.sql("DELETE FROM " + table + " WHERE route_version_id=?").param(routeVersionId).update();
        }
        jdbc.sql("DELETE FROM route_version WHERE id=?").param(routeVersionId).update();
        jdbc.sql("DELETE FROM route WHERE id=?").param(routeId).update();
        jdbc.sql("DELETE FROM model_deployment WHERE id IN (?,?)").params(deploymentId,nextDeploymentId).update();
    }

    @Test
    void 예보와_새_정산_작업과_입력_확정을_같이_저장한다() {
        committer.commit(batch, publication());
        assertThat(count("seat_forecast")).isOne();
        assertThat(count("alighting_forecast_evaluation_work")).isOne();
        assertThat(count("forecast_evaluation_work")).isZero();
        assertThat(confirmed()).isTrue();
    }

    @Test
    void 입력_확정에_실패하면_예보와_정산_등록도_롤백한다() {
        CollectionInputs failing = mock(CollectionInputs.class);
        doThrow(new IllegalStateException("confirm failed")).when(failing).confirmInput(batchId,GENERATED_AT);
        var writer = new ForecastBatchCommitter(publications,routeWork,failing);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> writer.commit(batch,publication())))
            .hasMessage("confirm failed");
        assertThat(count("forecast_publication")).isZero();
        assertThat(count("seat_forecast")).isZero();
        assertThat(count("alighting_forecast_evaluation_work")).isZero();
        assertThat(confirmed()).isFalse();
    }

    @Test
    void 재발행해도_원본과_정산_작업을_중복_생성하지_않는다() {
        committer.commit(batch,publication());
        committer.commit(batch,publication());
        assertThat(count("forecast_publication")).isOne();
        assertThat(count("seat_forecast")).isOne();
        assertThat(count("alighting_forecast_evaluation_work")).isOne();
    }

    @Test
    void 폐기한_품질_재확인_때문에_발행을_취소하지_않는다() {
        jdbc.sql("UPDATE route SET data_revision=2 WHERE id=?").param(routeId).update();
        committer.commit(batch,publication());
        assertThat(count("seat_forecast")).isOne();
        assertThat(confirmed()).isTrue();
    }

    @Test
    void 새_계산_버전도_이미_발행한_관측을_다른_배포로_덮어쓰지_않는다() {
        committer.commit(batch,publication());
        long next = jdbc.sql("""
            INSERT INTO model_deployment(deployment_key,release_id,model_key,model_version,bundle_digest,
                prediction_target_version,calculation_version,supported_scope_digest,data_until,state)
            SELECT ?,'next-release',model_key,model_version,bundle_digest,prediction_target_version,
                calculation_version,supported_scope_digest,data_until,'STAGED' FROM model_deployment WHERE id=?
            RETURNING id
            """).params(UUID.randomUUID(),deploymentId).query(Long.class).single();
        nextDeploymentId=next;
        {
            var another = new ForecastPublication(batchId,routeVersionId,next,0,1,OBSERVED_AT,
                GENERATED_AT,GENERATED_AT,List.of());
            assertThatThrownBy(() -> committer.commit(batch,another)).hasMessageContaining("다른 모델로 덮어쓸 수 없다");
            assertThat(jdbc.sql("SELECT model_deployment_id FROM forecast_publication WHERE source_batch_id=?")
                .param(batchId).query(Long.class).single()).isEqualTo(deploymentId);
        }
    }

    private ForecastPublication publication() {
        var target = new VehicleStopTarget(new ObservedVehicle("vehicle-1",routeVersionId,3,OBSERVED_AT,12,null),
            new RouteStop(routeVersionId,4,"stop-4",true));
        var forecast = SeatForecast.of(observationId,target,SeatForecastResult.alighting(12.5,.4),deploymentId,0,GENERATED_AT)
            .withAlightingAwareEvidence("{\"capacity\":44,\"capacityPolicy\":\"vehicle-capacity-snapshot-v1\"}",DIGEST,
                "alighting-capacity-snapshot-v2");
        return new ForecastPublication(batchId,routeVersionId,deploymentId,0,1,OBSERVED_AT,GENERATED_AT,GENERATED_AT,List.of(forecast));
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE route_version_id=?")
            .param(routeVersionId).query(Long.class).single();
    }

    private boolean confirmed() {
        return jdbc.sql("SELECT input_confirmed_at IS NOT NULL FROM observation_batch WHERE id=?")
            .param(batchId).query(Boolean.class).single();
    }

    private static OffsetDateTime offset(Instant at) { return at.atOffset(ZoneOffset.UTC); }
}
