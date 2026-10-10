package com.gustler.backend.forecasting.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

import com.gustler.backend.forecasting.application.statistics.StatisticsArchiveReader;
import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.application.statistics.DemandStatisticsPipeline;
import com.gustler.backend.forecasting.application.statistics.DemandStatisticsRebuildRequestStore;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch.Key;
import com.gustler.backend.forecasting.application.statistics.StatisticsArchiveReader.Row;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsRebuildRepository;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsVersion;
import com.gustler.backend.forecasting.domain.statistics.RebuildScope;
import com.gustler.backend.forecasting.domain.statistics.StopDemandCell;
import com.gustler.backend.forecasting.domain.statistics.TimeSlot;
import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 실제 PostgreSQL에서 삭제 전후를 비교한다. 외부 저장소만 대역이며 운영 데이터는 사용하지 않는다. */
@ForecastingIntegrationTest
@TestPropertySource(properties = "sal175.archive-rebuild-test=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ArchivedDemandStatisticsRebuildTest {
    private static final Instant UNTIL = Instant.parse("2026-10-09T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(UNTIL, ZoneId.of("Asia/Seoul"));
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired RouteDataQualityAccess quality;
    @Autowired DemandStatisticsPipeline pipeline;
    @Autowired DemandStatisticsRebuildRequestStore requests;
    @Autowired JdbcStopDemandStatisticsRepository statistics;
    @MockitoSpyBean DemandStatisticsRebuildRepository rebuilds;
    @MockitoBean StatisticsArchiveReader reader;
    @MockitoBean Clock clock;
    private JdbcEvaluationArchiveStore store;
    private long route;
    private long source;

    @BeforeEach
    void 과거에_완료된_정산이_있는_독립된_노선을_준비한다() {
        when(clock.instant()).thenReturn(UNTIL);
        when(clock.getZone()).thenReturn(CLOCK.getZone());
        store = new JdbcEvaluationArchiveStore(jdbc, quality, CLOCK, transactions);
        String key = UUID.randomUUID().toString().substring(0, 20);
        long routeId = jdbc.sql("""
            INSERT INTO route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name)
            VALUES(:key,'GBIS',:key,'이관 시험','출발','도착') RETURNING id
            """).param("key", key).query(Long.class).single();
        route = jdbc.sql("""
            INSERT INTO route_version(route_id,content_digest,valid_from)
            VALUES(:route,:digest,'2026-10-01T00:00:00Z') RETURNING id
            """).param("route", routeId).param("digest", "0".repeat(64)).query(Long.class).single();
        jdbc.sql("INSERT INTO route_data_quality(route_id) VALUES(?)").param(routeId).update();
        jdbc.sql("""
            INSERT INTO route_stop(route_version_id,stop_order,stop_id,name,direction,boarding_allowed)
            SELECT :route,n,'stop-'||n,'정류장','UP',true FROM generate_series(8,11) n
            """).param("route", route).update();
        long batch = jdbc.sql("""
            INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,
                requested_at,response_received_at,outcome,normalization_version,collection_strategy_version)
            VALUES(:route,'2026-10-08T10:00:00Z',1,:key,'2026-10-08T10:00:00Z','2026-10-08T10:00:00Z',
                'SUCCESS_ROWS','normalization-v1.0.0','adaptive-kst-v1.0.1') RETURNING id
            """).param("route", route).param("key", key).query(Long.class).single();
        source = jdbc.sql("""
            INSERT INTO vehicle_observation(observation_batch_id,route_version_id,source_row_number,
                vehicle_id,stop_order,stop_id,passed_stop_order,running_state,remaining_seats)
            VALUES(:batch,:route,0,'archive-bus',8,'stop-8',8,2,20) RETURNING id
            """).param("batch", batch).param("route", route).query(Long.class).single();
        long model = jdbc.sql("""
            INSERT INTO model_deployment(deployment_key,release_id,model_key,model_version,bundle_digest,
                prediction_target_version,calculation_version,supported_scope_digest,data_until,state)
            VALUES(:key,'archive-test','seat-full-chance','1',:digest,'SEAT_FULL_CHANCE_V1','CALCULATION_V1',
                :digest,'2026-10-01T00:00:00Z','STAGED') RETURNING id
            """).param("key", UUID.randomUUID()).param("digest", "0".repeat(64)).query(Long.class).single();
        long publication = jdbc.sql("""
            INSERT INTO forecast_publication(source_batch_id,route_version_id,model_deployment_id,
                demand_statistics_revision,quality_revision,observed_at,generated_at,published_at,prediction_count)
            VALUES(:batch,:route,:model,1,1,'2026-10-08T10:00:00Z','2026-10-08T10:00:00Z',
                '2026-10-08T10:00:00Z',3) RETURNING id
            """).param("batch", batch).param("route", route).param("model", model).query(Long.class).single();
        jdbc.sql("""
            INSERT INTO seat_forecast(vehicle_observation_id,target_stop_order,route_version_id,stops_to_target,
                model_deployment_id,demand_statistics_revision,seat_full_chance_raw,seat_full_chance,
                expected_seats,generated_at,quality_revision,publication_id)
            SELECT :source,n,:route,n-8,:model,1,0.5,0.5,10,'2026-10-08T10:00:00Z',1,:publication
            FROM generate_series(9,11) n
            """).param("source", source).param("route", route).param("model", model)
            .param("publication", publication).update();
        jdbc.sql("""
            INSERT INTO forecast_evaluation_result(vehicle_observation_id,target_stop_order,route_version_id,
                scoring_state,scored_at)
            VALUES(:source,9,:route,'LOST','2026-10-08T11:00:00Z'),
                (:source,10,:route,'LOST','2026-10-08T11:00:00Z')
            """).param("source", source).param("route", route).update();
    }

    @Test
    void 완료_정산을_DB에서_지워도_외부_원본으로_재집계한_통계는_같다() {
        // given
        settle(source, 5);
        finish();
        var before = cells();
        assertThat(before).hasSize(1);
        assertThat(before.getFirst().sampleCount()).isOne();
        var rows = archiveAndDelete(source);
        var reads = answer(rows);
        request();

        // when
        finish();

        // then
        assertThat(cells()).isEqualTo(before);
        assertThat(reads.get()).isOne();
        assertThat(liveCount(source)).isZero();
        assertThat(pipeline.step(route).status()).isEqualTo(DemandStatisticsPipeline.Step.Status.IDLE);
        assertThat(reads.get()).isOne();
    }

    @Test
    void DB에_남은_정산과_이관된_정산을_각각_한번씩_집계한다() {
        // given
        settle(source, 5);
        long retained = secondJourney();
        settle(retained, 10);
        finish();
        var before = cells();
        assertThat(before.getFirst().sampleCount()).isEqualTo(2);
        var reads = answer(archiveAndDelete(source));
        request();

        // when
        finish();

        // then
        assertThat(cells()).isEqualTo(before);
        assertThat(reads.get()).isOne();
        assertThat(liveCount(retained)).isOne();
    }

    @Test
    void 정기_갱신에서는_이미_반영한_이관_파일을_다시_다운로드하지_않는다() {
        // given
        settle(source, 5);
        var reads = answer(archiveAndDelete(source));
        finish();
        var before = cells();
        assertThat(reads.get()).isOne();
        when(clock.instant()).thenReturn(UNTIL.plusSeconds(6 * 60 * 60));

        // when
        finish();

        // then
        assertThat(reads.get()).isOne();
        assertThat(statistics.readAsOf(route, TimeSlot.EVENING,
            DemandStatisticsVersion.CURRENT_CALCULATION_VERSION, clock.instant()).cells()).isEqualTo(before);
    }

    @Test
    void 다운로드_중_DB_원본이_복원되면_준비한_파일_결과를_버리고_DB로_재시도한다() {
        // given
        settle(source, 5);
        var rows = archiveAndDelete(source);
        pipeline.step(route);
        var before = progress();
        when(reader.read(any())).thenAnswer(call -> {
            jdbc.sql("""
                INSERT INTO forecast_evaluation_result
                SELECT * FROM jsonb_populate_record(NULL::forecast_evaluation_result, CAST(? AS jsonb))
                """).param(rows.getFirst().originalJson()).update();
            return rows;
        });

        // when
        var step = pipeline.step(route);

        // then
        assertThat(step.status()).isEqualTo(DemandStatisticsPipeline.Step.Status.WAITING);
        assertThat(progress()).isEqualTo(before);
        assertThat(totalCount()).isZero();
        finish();
        assertThat(cells().getFirst().sampleCount()).isOne();
    }

    @Test
    void 재집계_시작_이후에_등록된_표본은_과거_재집계에서_먼저_합산하지_않는다() {
        // given
        settle(source, 5);
        var rows = archiveAndDelete(source);
        answer(rows);
        pipeline.step(route);
        jdbc.sql("""
            INSERT INTO stop_demand_pending_sample(route_version_id,prediction_observation_id,arrival_observation_id,
                vehicle_id,target_stop_order,arrived_at,scored_at,prediction_remaining_seats,arrival_remaining_seats)
            SELECT e.route_version_id,e.vehicle_observation_id,e.arrival_observation_id,'archive-bus',
                e.target_stop_order,e.arrived_at,e.scored_at,20,e.seats_on_arrival
            FROM jsonb_populate_record(NULL::forecast_evaluation_result, CAST(? AS jsonb)) e
            """).param(rows.getFirst().originalJson()).update();

        // when
        pipeline.step(route);

        // then
        assertThat(totalCount()).isZero();
        finish();
        assertThat(cells().getFirst().sampleCount()).isOne();
    }

    @Test
    void 이관_검증만_끝나고_DB에_원본이_남아있으면_외부에서_중복해서_읽지_않는다() {
        // given
        settle(source, 5);
        archive(source);

        // when
        finish();

        // then
        assertThat(cells().getFirst().sampleCount()).isOne();
        verifyNoInteractions(reader);
    }

    @Test
    void 이관_후_편도를_품질_제외하면_같은_차량의_다른_편도_통계는_유지한다() {
        // given
        settle(source, 5);
        long retained = secondJourney();
        settle(retained, 10);
        finish();
        assertThat(cells().getFirst().sampleCount()).isEqualTo(2);
        answer(archiveAndDelete(source));
        excludeJourney();

        // when
        finish();

        // then
        assertThat(cells()).hasSize(1);
        assertThat(cells().getFirst().sampleCount()).isOne();
        assertThat(cells().getFirst().averageFillRate()).isEqualTo(0.5);
        assertThat(cells().getFirst().averageNetBoardingRate()).isEqualTo(0.5);
    }

    @Test
    void 외부_다운로드가_실패하면_진행_위치와_공개_통계를_바꾸지_않고_재시도한다() {
        // given
        settle(source, 5);
        var rows = archiveAndDelete(source);
        pipeline.step(route);
        var before = progress();
        when(reader.read(any())).thenThrow(new IllegalStateException("시험용 외부 저장소 장애"));

        // when & then
        assertThatThrownBy(() -> pipeline.step(route)).isInstanceOf(IllegalStateException.class);
        assertThat(progress()).isEqualTo(before);
        assertThat(totalCount()).isZero();
        assertThat(statistics.currentRevision(route, DemandStatisticsVersion.CURRENT_CALCULATION_VERSION)).isZero();

        // when
        answer(rows);
        finish();

        // then
        assertThat(cells().getFirst().sampleCount()).isOne();
    }

    @Test
    void 다운로드_중_품질_판정이_바뀌면_이전_입력은_버리고_새_판정으로_재시도한다() {
        // given
        settle(source, 5);
        var rows = archiveAndDelete(source);
        pipeline.step(route);
        var before = progress();
        when(reader.read(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            excludeJourney();
            return rows;
        });

        // when
        var step = pipeline.step(route);

        // then
        assertThat(step.status()).isEqualTo(DemandStatisticsPipeline.Step.Status.WAITING);
        assertThat(progress()).isEqualTo(before);
        assertThat(totalCount()).isZero();

        // when
        answer(rows);
        finish();

        // then
        assertThat(cells()).isEmpty();
    }

    @Test
    void 다운로드_중_새_재집계_요청이_오면_기존_요청의_결과를_반영하지_않는다() {
        // given
        settle(source, 5);
        var rows = archiveAndDelete(source);
        pipeline.step(route);
        var before = progress();
        when(reader.read(any())).thenAnswer(call -> { request(); return rows; });

        // when
        var step = pipeline.step(route);

        // then
        assertThat(step.status()).isEqualTo(DemandStatisticsPipeline.Step.Status.WAITING);
        assertThat(progress()).isEqualTo(before);
        assertThat(totalCount()).isZero();
    }

    @Test
    void 다운로드_중_다른_실행자가_같은_페이지를_완료하면_중복_합산하지_않는다() {
        // given
        settle(source, 5);
        var rows = archiveAndDelete(source);
        pipeline.step(route);
        var entered = new java.util.concurrent.atomic.AtomicBoolean();
        when(reader.read(any())).thenAnswer(call -> {
            if (entered.compareAndSet(false, true)) pipeline.step(route);
            return rows;
        });

        // when
        var step = pipeline.step(route);

        // then
        assertThat(step.status()).isEqualTo(DemandStatisticsPipeline.Step.Status.WAITING);
        assertThat(totalCount()).isOne();
        finish();
        assertThat(cells().getFirst().sampleCount()).isOne();
    }

    @Test
    void 합계를_저장한_뒤_진행_저장에_실패하면_둘_다_롤백한다() {
        // given
        settle(source, 5);
        answer(archiveAndDelete(source));
        pipeline.step(route);
        var before = progress();
        doAnswer(call -> { throw new IllegalStateException("시험용 진행 저장 장애"); }).when(rebuilds).save(any());

        // when & then
        assertThatThrownBy(() -> pipeline.step(route))
            .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
            .hasRootCauseMessage("시험용 진행 저장 장애");
        assertThat(progress()).isEqualTo(before);
        assertThat(totalCount()).isZero();
    }

    @Test
    void 파일에_예약한_원본이_없으면_누락된_통계를_공개하지_않는다() {
        // given
        settle(source, 5);
        archiveAndDelete(source);
        pipeline.step(route);
        var before = progress();
        answer(List.of());

        // when & then
        assertThatThrownBy(() -> pipeline.step(route)).isInstanceOf(IllegalStateException.class);
        assertThat(progress()).isEqualTo(before);
        assertThat(totalCount()).isZero();
    }

    @Test
    void 기존_DB전용_일괄_집계는_이관된_원본이_빠진_결과를_반환하지_않는다() {
        // given
        settle(source, 5);
        archiveAndDelete(source);

        // when & then
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status ->
            statistics.readHourlyTotals(route, UNTIL)))
            .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
            .hasRootCauseMessage("DB 원본만 읽는 일괄 집계는 이관된 정산을 처리할 수 없다");
    }

    @Test
    void 외부_원본이_필요한_재집계를_기존_트랜잭션으로_감싸면_다운로드하지_않고_거부한다() {
        // given
        settle(source, 5);
        archiveAndDelete(source);
        pipeline.step(route);

        // when & then
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status ->
            pipeline.step(route)))
            .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
            .hasRootCauseMessage("이관된 정산을 제외한 DB 전용 재집계는 실행하지 않는다");
        verifyNoInteractions(reader);
    }

    private List<Row> archive(long id) {
        var batch = store.reserve(route, List.of(new Key(id, 9))).orElseThrow();
        var rows = store.readOwned(batch).stream().map(row -> new Row(
            new StatisticsArchiveReader.Key(row.key().observationId(), row.key().stopOrder()),
            row.originalJson(), row.sha256())).toList();
        // 저장소 계약 대역: 파일 해시와 객체 왕복은 EvaluationArchiveObjectStoreTest에서 별도 검증한다.
        store.recordVerified(batch, "a".repeat(64));
        return rows;
    }

    private List<Row> archiveAndDelete(long id) {
        var rows = archive(id);
        jdbc.sql("DELETE FROM forecast_evaluation_result WHERE vehicle_observation_id=? AND target_stop_order=9")
            .param(id).update();
        return rows;
    }

    private AtomicInteger answer(List<Row> rows) {
        var reads = new AtomicInteger();
        // 기존 실패 stub을 호출하지 않고 다시 설정한다.
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            reads.incrementAndGet();
            return rows;
        }).when(reader).read(any());
        return reads;
    }

    private void request() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            quality.lock(route);
            requests.request(route, RebuildScope.wholeRoute());
        });
    }

    private void excludeJourney() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            quality.lock(route);
            jdbc.sql("UPDATE vehicle_one_way_trip SET status='EXCLUDED' WHERE id=?")
                .param("journey-" + source).update();
            jdbc.sql("""
                UPDATE route_data_quality SET quality_revision=quality_revision+1
                WHERE route_id=(SELECT route_id FROM route_version WHERE id=?)
                """).param(route).update();
            requests.request(route, RebuildScope.wholeRoute());
        });
    }

    private long secondJourney() {
        long batch = batch("2026-10-08T10:30:00Z");
        long id = jdbc.sql("""
            INSERT INTO vehicle_observation(observation_batch_id,route_version_id,source_row_number,
                vehicle_id,stop_order,stop_id,passed_stop_order,running_state,remaining_seats)
            VALUES(:batch,:route,0,'archive-bus',8,'stop-8',8,2,20) RETURNING id
            """).param("batch", batch).param("route", route).query(Long.class).single();
        long publication = jdbc.sql("""
            INSERT INTO forecast_publication(source_batch_id,route_version_id,model_deployment_id,
                demand_statistics_revision,quality_revision,observed_at,generated_at,published_at,prediction_count)
            SELECT :batch,route_version_id,model_deployment_id,1,1,'2026-10-08T10:30:00Z',
                '2026-10-08T10:30:00Z','2026-10-08T10:30:00Z',1
            FROM forecast_publication WHERE route_version_id=:route LIMIT 1 RETURNING id
            """).param("batch", batch).param("route", route).query(Long.class).single();
        jdbc.sql("""
            INSERT INTO seat_forecast(vehicle_observation_id,target_stop_order,route_version_id,stops_to_target,
                model_deployment_id,demand_statistics_revision,seat_full_chance_raw,seat_full_chance,
                expected_seats,generated_at,quality_revision,publication_id)
            SELECT :id,9,route_version_id,1,model_deployment_id,1,0.5,0.5,10,'2026-10-08T10:30:00Z',1,:publication
            FROM seat_forecast WHERE vehicle_observation_id=:source AND target_stop_order=9
            """).param("id", id).param("source", source).param("publication", publication).update();
        jdbc.sql("""
            INSERT INTO forecast_evaluation_result(vehicle_observation_id,target_stop_order,route_version_id,scoring_state,scored_at)
            VALUES(:id,9,:route,'LOST','2026-10-08T13:00:00Z')
            """).param("id", id).param("route", route).update();
        return id;
    }

    private void settle(long id, int seats) {
        String at = id == source ? "2026-10-08T10:10:00Z" : "2026-10-08T10:40:00Z";
        long batch = batch(at);
        long arrival = jdbc.sql("""
            INSERT INTO vehicle_observation(observation_batch_id,route_version_id,source_row_number,
                vehicle_id,stop_order,stop_id,passed_stop_order,running_state,remaining_seats)
            VALUES(:batch,:route,0,'archive-bus',9,'stop-9',9,2,:seats) RETURNING id
            """).param("batch", batch).param("route", route).param("seats", seats).query(Long.class).single();
        jdbc.sql("""
            INSERT INTO vehicle_one_way_trip(id,start_observation_id,route_version_id,vehicle_id,status,boundary,rule_version)
            VALUES(:trip,:id,:route,'archive-bus','ELIGIBLE','DEPARTURE','archive-rebuild-test')
            """).param("trip", "journey-" + id).param("id", id).param("route", route).update();
        jdbc.sql("""
            INSERT INTO observation_trip_assignment(observation_id,trip_id) VALUES(:id,:trip),(:arrival,:trip)
            """).param("id", id).param("arrival", arrival).param("trip", "journey-" + id).update();
        jdbc.sql("""
            UPDATE forecast_evaluation_result SET scoring_state='SETTLED',arrival_observation_id=:arrival,
                seats_on_arrival=:seats,arrived_at=CAST(:at AS timestamptz),arrival_route_version_id=:route,
                arrival_vehicle_id='archive-bus',arrival_stop_order=9,arrival_running_state=2,
                arrival_remaining_seats=:seats,arrival_quality_direction=0
            WHERE vehicle_observation_id=:id AND target_stop_order=9
            """).param("arrival", arrival).param("seats", seats).param("at", at).param("route", route).param("id", id).update();
    }

    private long batch(String at) {
        return jdbc.sql("""
            INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,
                requested_at,response_received_at,outcome,normalization_version,collection_strategy_version)
            VALUES(:route,CAST(:at AS timestamptz),1,:key,CAST(:at AS timestamptz),CAST(:at AS timestamptz),
                'SUCCESS_ROWS','normalization-v1.0.0','adaptive-kst-v1.0.1') RETURNING id
            """).param("route", route).param("at", at).param("key", UUID.randomUUID().toString().substring(0,20))
            .query(Long.class).single();
    }

    private void finish() {
        for (int step = 0; step < 100; step++) {
            if (pipeline.step(route).status() == DemandStatisticsPipeline.Step.Status.COMPLETED) return;
        }
        throw new AssertionError("재집계가 완료되지 않았다");
    }

    private List<StopDemandCell> cells() {
        return statistics.readAsOf(route, TimeSlot.EVENING, DemandStatisticsVersion.CURRENT_CALCULATION_VERSION, UNTIL).cells();
    }

    private java.util.Map<String, Object> progress() {
        return jdbc.sql("SELECT * FROM stop_demand_rebuild_progress WHERE route_version_id=?")
            .param(route).query().singleRow();
    }

    private long totalCount() {
        return jdbc.sql("SELECT COALESCE(sum(sample_count),0) FROM stop_demand_rebuild_total WHERE route_version_id=?")
            .param(route).query(Long.class).single();
    }

    private long liveCount(long id) {
        return jdbc.sql("SELECT count(*) FROM forecast_evaluation_result WHERE vehicle_observation_id=? AND target_stop_order=9")
            .param(id).query(Long.class).single();
    }
}
