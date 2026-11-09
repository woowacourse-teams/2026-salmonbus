package com.gustler.backend.forecasting.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.Mockito.when;

import com.gustler.backend.forecasting.application.statistics.DemandAccumulator;
import com.gustler.backend.forecasting.application.statistics.DemandStatisticsPipeline;
import com.gustler.backend.forecasting.domain.statistics.DemandSampleStore;
import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 운영 원본 없이 누적 반영과 최종 발행이 가능한지 실제 PG에서 검증한다. */
@ForecastingIntegrationTest
class IncrementalStatisticsWithoutHistoryTest {
    @Autowired
    JdbcClient jdbc;
    @Autowired
    DemandAccumulator accumulator;
    @Autowired
    DemandStatisticsPipeline pipeline;
    @Autowired
    DemandSampleStore samples;
    @Autowired
    PlatformTransactionManager transactions;
    @MockitoBean
    Clock clock;
    long version;
    long routeId;
    String vehicle;

    @BeforeEach
    void 새_노선과_정산_수요_표본을_준비한다() {
        when(clock.instant()).thenReturn(Instant.parse("2026-11-09T03:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
        String key = UUID.randomUUID().toString().substring(0, 20);
        vehicle = "stats-" + key;
        routeId = jdbc.sql(
            "INSERT INTO route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name) VALUES(?,'GBIS',?,'3330','A','B') RETURNING id")
            .params(key, key).query(Long.class).single();
        version = jdbc
            .sql("INSERT INTO route_version(route_id,content_digest,valid_from) VALUES(?,?,'2026-01-01') RETURNING id")
            .params(routeId, "0".repeat(64)).query(Long.class).single();
        jdbc.sql(
            "INSERT INTO route_stop(route_version_id,stop_order,stop_id,name,direction,boarding_allowed) VALUES(?,1,'s1','S1','UP',true),(?,2,'s2','S2','UP',true)")
            .params(version, version).update();
        jdbc.sql(
            """
                INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,response_received_at,outcome,normalization_version,collection_strategy_version)
                VALUES(?,'2026-11-07T03:00Z',1,?,'2026-11-07T03:00Z','SUCCESS_ROWS','n','c')
                """)
            .params(version, key).update();
        final long batch = jdbc.sql("SELECT id FROM observation_batch WHERE route_version_id=?").param(version)
            .query(Long.class).single();
        final long arrivalBatch = jdbc
            .sql(
                """
                    INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,response_received_at,outcome,normalization_version,collection_strategy_version)
                    VALUES(?,'2026-11-07T03:10Z',1,?,'2026-11-07T03:10Z','SUCCESS_ROWS','n','c') RETURNING id
                    """)
            .params(version, key + "-arrival").query(Long.class).single();
        jdbc.sql("""
            INSERT INTO vehicle_observation(observation_batch_id,route_version_id,source_row_number,vehicle_id,
                stop_order,stop_id,running_state,passed_stop_order,remaining_seats)
            VALUES(?,?,1,?,1,'s1',2,1,12),(?,?,2,?,2,'s2',2,2,9)
            """).params(batch, version, vehicle, arrivalBatch, version, vehicle).update();
        jdbc.sql(
            """
                INSERT INTO stop_demand_pending_sample(route_version_id,prediction_observation_id,arrival_observation_id,
                    vehicle_id,target_stop_order,arrived_at,scored_at,prediction_remaining_seats,arrival_remaining_seats)
                SELECT ?,min(id),max(id),?,2,'2026-11-07T03:10Z','2026-11-07T03:11Z',12,9 FROM vehicle_observation WHERE route_version_id=?
                """)
            .params(version, vehicle, version).update();
        jdbc.sql(
            "INSERT INTO vehicle_capacity_history(source_id,vehicle_id,evidence_at,evidence_batch_id,capacity) VALUES('GBIS',?,'2026-11-01',0,45)")
            .param(vehicle).update();
    }

    @Test
    void 정산_표본은_합계에_한번만_반영하고_제거한다() {

        // when
        final long through = samples.lastSampleId();
        accumulator.apply(version, vehicle, 0, through, clock.instant());
        accumulator.apply(version, vehicle, 0, through, clock.instant());

        // then
        assertSoftly(softly -> {
            softly.assertThat(jdbc.sql("SELECT sample_count FROM stop_demand_current_total WHERE route_version_id=?")
                .param(version).query(Long.class).single()).isEqualTo(1);
            softly.assertThat(jdbc.sql("SELECT net_boarding_sum FROM stop_demand_current_total WHERE route_version_id=?")
                .param(version).query(Long.class).single()).isEqualTo(3);
            softly.assertThat(pending()).isZero();
        });
    }

    @Test
    void 원본을_삭제해도_정원_이력과_누적_합계로_예보용_통계를_발행한다() {
        // given
        accumulator.apply(version, vehicle, 0, samples.lastSampleId(), clock.instant());
        jdbc.sql("DELETE FROM vehicle_observation WHERE route_version_id=?").param(version).update();
        jdbc.sql("DELETE FROM observation_batch WHERE route_version_id=?").param(version).update();

        // when
        boolean published = false;
        for (int step = 0; step < 100; step++) {
            if (pipeline.step(version).status() == DemandStatisticsPipeline.Step.Status.COMPLETED) {
                published = true;
                break;
            }
        }

        // then
        assertThat(published).isTrue();
        assertSoftly(softly -> {
            softly.assertThat(jdbc.sql("SELECT average_fill_rate FROM stop_demand_statistics WHERE route_version_id=?")
                .param(version).query(Double.class).single()).isCloseTo(0.8, within(0.000001));
            softly.assertThat(jdbc.sql("SELECT average_net_boarding_rate FROM stop_demand_statistics WHERE route_version_id=?")
                .param(version).query(Double.class).single()).isCloseTo(3.0 / 45, within(0.000001));
            softly.assertThat(jdbc.sql("SELECT sample_count FROM stop_demand_current_total WHERE route_version_id=?")
                .param(version).query(Long.class).single()).isEqualTo(1);
        });
    }

    @Test
    void 누적_반영이_롤백되면_표본도_삭제되지_않는다() {

        // when
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            accumulator.apply(version, vehicle, 0, samples.lastSampleId(), clock.instant());
            status.setRollbackOnly();
        });

        // then
        assertSoftly(softly -> {
            softly.assertThat(pending()).isOne();
            softly.assertThat(jdbc.sql("SELECT count(*) FROM stop_demand_current_total WHERE route_version_id=?").param(version)
                .query(Long.class).single()).isZero();
        });
    }

    @AfterEach
    void 사용한_운영_상태와_노선을_정리한다() {
        for (String table : List.of("stop_demand_pending_sample", "stop_demand_current_total", "stop_demand_vehicle",
            "stop_demand_capacity_stage", "stop_demand_day_stage", "stop_demand_cell_stage", "stop_demand_statistics",
            "demand_statistics_version", "stop_demand_run", "stop_demand_baseline", "vehicle_observation",
            "observation_batch", "route_stop")) {
            jdbc.sql("DELETE FROM " + table + " WHERE route_version_id=?").param(version).update();
        }
        jdbc.sql("DELETE FROM route_version WHERE id=?").param(version).update();
        jdbc.sql("DELETE FROM route WHERE id=?").param(routeId).update();
        jdbc.sql("DELETE FROM vehicle_capacity_history WHERE vehicle_id=?").param(vehicle).update();
    }

    long pending() {
        return jdbc.sql("SELECT count(*) FROM stop_demand_pending_sample WHERE route_version_id=?").param(version)
            .query(Long.class).single();
    }
}
