package com.gustler.backend.forecasting.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.gustler.backend.forecasting.application.evaluation.EvaluationArchiveSource;
import com.gustler.backend.forecasting.application.evaluation.PurgeArchivedEvaluationsService;
import com.gustler.backend.forecasting.application.evaluation.RestoreArchivedEvaluationsService;
import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.application.statistics.DemandStatisticsPipeline;
import com.gustler.backend.forecasting.application.statistics.StatisticsArchiveReader;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch.Key;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch.Row;
import com.gustler.backend.forecasting.domain.evaluation.ForecastEvaluation;
import com.gustler.backend.forecasting.domain.evaluation.ForecastEvaluationRepository;
import com.gustler.backend.forecasting.domain.evaluation.SeoulDay;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsVersion;
import com.gustler.backend.forecasting.domain.statistics.TimeSlot;
import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

/** 삭제는 이 테스트 전용 PostgreSQL에서만 실행한다. 외부 저장소는 원본 목록 대역이다. */
@ForecastingIntegrationTest
@TestPropertySource(properties = "sal175.archive-retention-test=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EvaluationArchiveRetentionTest {
    private static final Instant UNTIL = Instant.parse("2026-10-09T00:00:00Z");
    @MockitoSpyBean JdbcClient jdbc;
    @MockitoBean Clock clock;
    @MockitoBean StatisticsArchiveReader statisticsReader;
    @Autowired PlatformTransactionManager transactions;
    @Autowired RouteDataQualityAccess quality;
    @Autowired DemandStatisticsPipeline pipeline;
    @Autowired JdbcStopDemandStatisticsRepository statistics;
    @Autowired JdbcSameDayFullOutcomesStore outcomes;
    @Autowired ForecastEvaluationRepository evaluations;
    @Autowired JdbcEvaluationArchiveQueue queue;
    private JdbcEvaluationArchiveStore store;
    private long route;
    private long source;
    private final Map<UUID, List<Row>> archived = new HashMap<>();
    private final AtomicInteger downloads = new AtomicInteger();
    private final EvaluationArchiveSource external = batch -> {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        downloads.incrementAndGet();
        return archived.get(batch.id());
    };

    @BeforeEach
    void 과거_완료_정산이_있는_독립된_노선을_준비한다() {
        when(clock.instant()).thenReturn(UNTIL);
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(statisticsReader.read(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            StatisticsArchiveReader.Reference reference = call.getArgument(0);
            return archived.get(reference.batchId()).stream().map(row -> new StatisticsArchiveReader.Row(
                new StatisticsArchiveReader.Key(row.key().observationId(), row.key().stopOrder()),
                row.originalJson(), row.sha256())).toList();
        });
        store = new JdbcEvaluationArchiveStore(jdbc, quality, clock, transactions);
        jdbc.sql("UPDATE evaluation_archive_scan SET next_attempt_at='-infinity' WHERE id=1").update();
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
    void 파일_검증이_끝나지_않은_정산은_삭제하지_않는다() {
        // given
        finish();
        var batch = store.reserve(route, keys()).orElseThrow();

        // when & then
        assertThatThrownBy(() -> purge(batch)).isInstanceOf(IllegalStateException.class);
        assertThat(liveCount()).isEqualTo(2);
        assertThat(downloads.get()).isZero();
    }

    @Test
    void 첫_집계가_끝나지_않은_노선은_원본을_유지한다() {
        // given
        var batch = archive();

        // when & then
        assertThatThrownBy(() -> purge(batch)).isInstanceOf(IllegalStateException.class);
        assertThat(liveCount()).isEqualTo(2);
        assertThat(location(batch)).isEqualTo("LIVE");
    }

    @Test
    void 통계의_기준_시각이_정산_완료보다_이르면_삭제하지_않는다() {
        // given
        var batch = ready();
        jdbc.sql("UPDATE stop_demand_run SET data_until='2026-10-08T09:00:00Z' WHERE route_version_id=?")
            .param(route).update();

        // when & then
        assertThatThrownBy(() -> purge(batch)).isInstanceOf(IllegalStateException.class);
        assertThat(liveCount()).isEqualTo(2);
    }

    @Test
    void 아직_집계할_표본이_남아_있으면_원본을_삭제하지_않는다() {
        // given
        settle();
        var batch = ready();
        addSample();

        // when & then
        assertThatThrownBy(() -> purge(batch)).isInstanceOf(IllegalStateException.class);
        assertThat(liveCount()).isEqualTo(2);
    }

    @Test
    void 과거_재집계가_요청되어_있으면_원본을_삭제하지_않는다() {
        // given
        var batch = ready();
        jdbc.sql("INSERT INTO stop_demand_rebuild_request(route_version_id,vehicle_id,request_id) VALUES(?,'',?)")
            .params(route, UUID.randomUUID()).update();

        // when & then
        assertThatThrownBy(() -> purge(batch)).isInstanceOf(IllegalStateException.class);
        assertThat(liveCount()).isEqualTo(2);
    }

    @Test
    void 외부_파일을_다시_읽지_못하면_원본과_위치_기록을_유지한다() {
        // given
        var batch = ready();
        var service = new PurgeArchivedEvaluationsService(store, ignored -> {
            throw new IllegalStateException("시험용 저장소 장애");
        });

        // when & then
        assertThatThrownBy(() -> service.purge(batch)).hasMessage("시험용 저장소 장애");
        assertThat(liveCount()).isEqualTo(2);
        assertThat(location(batch)).isEqualTo("LIVE");
    }

    @Test
    void 이관_검증_후_내용이_바뀐_정산은_삭제하지_않는다() {
        // given
        var batch = ready();
        jdbc.sql("UPDATE forecast_evaluation_result SET scoring_state='SKIPPED' WHERE vehicle_observation_id=?")
            .param(source).update();

        // when & then
        assertThatThrownBy(() -> purge(batch)).isInstanceOf(IllegalStateException.class);
        assertThat(liveCount()).isEqualTo(2);
    }

    @Test
    void 다운로드_중_품질_판정이_바뀌면_삭제하지_않는다() {
        // given
        var batch = ready();
        var service = new PurgeArchivedEvaluationsService(store, selected -> {
            changeQuality();
            return archived.get(selected.id());
        });

        // when & then
        assertThatThrownBy(() -> service.purge(batch)).isInstanceOf(IllegalStateException.class);
        assertThat(liveCount()).isEqualTo(2);
        assertThat(location(batch)).isEqualTo("LIVE");
    }

    @Test
    void 다운로드_중_집계_표본이_생기면_삭제하지_않는다() {
        // given
        settle();
        var batch = ready();
        var service = new PurgeArchivedEvaluationsService(store, selected -> {
            addSample();
            return archived.get(selected.id());
        });

        // when & then
        assertThatThrownBy(() -> service.purge(batch)).isInstanceOf(IllegalStateException.class);
        assertThat(liveCount()).isEqualTo(2);
    }

    @Test
    void 다른_작업자에게_소유권이_넘어가면_이전_작업자는_삭제하지_못한다() {
        // given
        var batch = ready();
        var service = new PurgeArchivedEvaluationsService(store, selected -> {
            jdbc.sql("UPDATE evaluation_archive_batch SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?")
                .param(selected.id()).update();
            store.reclaim(selected.id()).orElseThrow();
            return archived.get(selected.id());
        });

        // when & then
        assertThatThrownBy(() -> service.purge(batch)).isInstanceOf(IllegalStateException.class);
        assertThat(liveCount()).isEqualTo(2);
    }

    @Test
    void 삭제_기록_저장이_실패하면_삭제한_원본도_함께_되돌린다() {
        // given
        var batch = ready();
        failReceipt();

        // when & then
        assertThatThrownBy(() -> purge(batch)).isInstanceOf(IllegalStateException.class);
        assertThat(liveCount()).isEqualTo(2);
        assertThat(location(batch)).isEqualTo("LIVE");
    }

    @Test
    void 삭제_커밋_뒤_재시도해도_다시_삭제하거나_정산_대기를_만들지_않는다() {
        // given
        var batch = ready();

        // when
        int first = purge(batch);
        int repeated = purge(batch);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            quality.lock(route);
            evaluations.addPending(route, List.of(ForecastEvaluation.pending(source, 9), ForecastEvaluation.pending(source, 10)));
        });

        // then
        assertThat(first).isEqualTo(2);
        assertThat(repeated).isZero();
        assertThat(liveCount()).isZero();
        assertThat(location(batch)).isEqualTo("PURGED");
        assertThat(downloads.get()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM evaluation_archive_member WHERE batch_id=?")
            .param(batch.id()).query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM forecast_evaluation_pending WHERE route_version_id=?")
            .param(route).query(Long.class).single()).isZero();
    }

    @Test
    void 원본을_복원하고_재시도해도_통계와_정산은_중복되지_않는다() {
        // given
        settle();
        var batch = ready();
        var before = cells();
        assertThat(before.getFirst().sampleCount()).isOne();
        purge(batch);

        // when
        int restored = restore(batch);
        int repeated = restore(batch);

        // then
        assertThat(restored).isEqualTo(2);
        assertThat(repeated).isZero();
        assertThat(store.readOwned(batch)).isEqualTo(archived.get(batch.id()));
        assertThat(location(batch)).isEqualTo("LIVE");
        assertThat(cells()).isEqualTo(before);
        assertThat(pipeline.step(route).status()).isEqualTo(DemandStatisticsPipeline.Step.Status.IDLE);
        assertThat(jdbc.sql("SELECT count(*) FROM stop_demand_pending_sample WHERE route_version_id=?")
            .param(route).query(Long.class).single()).isZero();
    }

    @Test
    void 실제_삭제와_복원을_거쳐_재집계해도_기존_통계가_유지된다() {
        // given
        settle();
        var batch = ready();
        var before = cells();

        // when
        purge(batch);
        rebuild();
        var afterPurge = cells();
        restore(batch);
        rebuild();

        // then
        assertThat(afterPurge).isEqualTo(before);
        assertThat(cells()).isEqualTo(before);
        assertThat(store.readOwned(batch)).isEqualTo(archived.get(batch.id()));
    }

    @Test
    void 복원_다운로드_중_품질_판정이_바뀌면_반영하지_않고_다시_준비한다() {
        // given
        var batch = ready();
        purge(batch);
        var service = new RestoreArchivedEvaluationsService(store, selected -> {
            changeQuality();
            return archived.get(selected.id());
        });

        // when & then
        assertThatThrownBy(() -> service.restore(batch)).isInstanceOf(IllegalStateException.class);
        assertThat(liveCount()).isZero();
        assertThat(location(batch)).isEqualTo("PURGED");
        assertThat(restore(batch)).isEqualTo(2);
    }

    @Test
    void 삭제_검증은_바깥_DB_트랜잭션을_유지한_채_시작하지_않는다() {
        // given
        var batch = ready();

        // when & then
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> purge(batch)))
            .isInstanceOf(IllegalStateException.class);
        assertThat(downloads.get()).isZero();
        assertThat(liveCount()).isEqualTo(2);
    }

    @Test
    void 일부_원본이_이미_동일하게_복원되어_있으면_나머지만_복원한다() {
        // given
        var batch = ready();
        purge(batch);
        insert(archived.get(batch.id()).getFirst());

        // when
        int restored = restore(batch);

        // then
        assertThat(restored).isOne();
        assertThat(store.readOwned(batch)).isEqualTo(archived.get(batch.id()));
    }

    @Test
    void 현재_행과_보존_원본이_다르면_덮어쓰거나_일부만_복원하지_않는다() {
        // given
        var batch = ready();
        purge(batch);
        insert(archived.get(batch.id()).getLast());
        jdbc.sql("UPDATE forecast_evaluation_result SET scoring_state='SKIPPED' WHERE vehicle_observation_id=?")
            .param(source).update();

        // when & then
        assertThatThrownBy(() -> restore(batch)).isInstanceOf(IllegalStateException.class);
        assertThat(liveCount()).isOne();
        assertThat(jdbc.sql("SELECT scoring_state FROM forecast_evaluation_result WHERE vehicle_observation_id=?")
            .param(source).query(String.class).single()).isEqualTo("SKIPPED");
        assertThat(location(batch)).isEqualTo("PURGED");
    }

    @Test
    void 복원_기록_저장이_실패하면_복원한_행도_함께_되돌린다() {
        // given
        var batch = ready();
        purge(batch);
        failReceipt();

        // when & then
        assertThatThrownBy(() -> restore(batch)).isInstanceOf(IllegalStateException.class);
        assertThat(liveCount()).isZero();
        assertThat(location(batch)).isEqualTo("PURGED");
    }

    @Test
    void 다운로드_중_삭제와_복원이_끝나면_오래된_준비_정보로_재삭제하지_않는다() {
        // given
        var batch = ready();
        var stale = store.preparePurge(batch);
        purge(batch);
        restore(batch);

        // when & then
        assertThatThrownBy(() -> store.purge(stale, archived.get(batch.id()))).isInstanceOf(IllegalStateException.class);
        assertThat(liveCount()).isEqualTo(2);
        assertThat(location(batch)).isEqualTo("LIVE");
    }

    @Test
    void 품질_판정이_변한_뒤에도_원본은_복원하되_통계를_다시_더하지_않는다() {
        // given
        var batch = ready();
        purge(batch);
        changeQuality();

        // when
        int restored = restore(batch);

        // then
        assertThat(restored).isEqualTo(2);
        assertThat(store.prepareRestore(batch).currentQuality()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM stop_demand_pending_sample WHERE route_version_id=?")
            .param(route).query(Long.class).single()).isZero();
    }

    @Test
    void 다운로드_중_복원된_묶음을_이전_실행자가_다시_복원하지_않는다() {
        // given
        var batch = ready();
        purge(batch);
        var stale = store.prepareRestore(batch);
        restore(batch);

        // when & then
        assertThatThrownBy(() -> store.restore(stale, archived.get(batch.id()))).isInstanceOf(IllegalStateException.class);
        assertThat(liveCount()).isEqualTo(2);
    }

    @Test
    void 보존한_원본의_검증값이_다르면_삭제하지_않는다() {
        // given
        var batch = ready();
        var rows = archived.get(batch.id());
        var changed = new Row(rows.getFirst().key(), rows.getFirst().originalJson(), "b".repeat(64));
        var service = new PurgeArchivedEvaluationsService(store, ignored -> List.of(changed, rows.getLast()));

        // when & then
        assertThatThrownBy(() -> service.purge(batch)).isInstanceOf(IllegalArgumentException.class);
        assertThat(liveCount()).isEqualTo(2);
    }

    @Test
    void 과거_정산을_삭제해도_오늘의_보정값은_남은_원본에서_복구할_수_있다() {
        // given
        settle();
        var batch = ready();
        long todayArrival = arrival("2026-10-08T23:00:00Z", 11);
        jdbc.sql("""
            INSERT INTO forecast_evaluation_result
            SELECT (jsonb_populate_record(NULL::forecast_evaluation_result,to_jsonb(e)
                || '{"target_stop_order":11,"arrival_stop_order":11,"arrived_at":"2026-10-08T23:00:00Z","scored_at":"2026-10-08T23:30:00Z"}'::jsonb
                || jsonb_build_object('arrival_observation_id',CAST(:arrival AS bigint)))).*
            FROM forecast_evaluation_result e WHERE vehicle_observation_id=:source AND target_stop_order=9
            """).param("source", source).param("arrival", todayArrival).update();
        long routeId = jdbc.sql("SELECT route_id FROM route_version WHERE id=?").param(route).query(Long.class).single();
        long model = jdbc.sql("SELECT model_deployment_id FROM seat_forecast WHERE vehicle_observation_id=? AND target_stop_order=11")
            .param(source).query(Long.class).single();
        var day = SeoulDay.containing(UNTIL);
        var before = outcomes.countFromSource(routeId, model, day, UNTIL);
        assertThat(before).hasSize(1);

        // when
        purge(batch);
        var after = outcomes.countFromSource(routeId, model, day, UNTIL);

        // then
        assertThat(after).isEqualTo(before);
        assertThat(after.getFirst().rowCount()).isOne();
        assertThat(liveCount()).isOne();
    }

    @Test
    void 자동_실행은_집계된_과거_정산을_찾아_보존한_뒤_원본을_삭제한다() {
        // given
        finish();
        seekCurrent();

        // when
        automatic(true).advance();

        // then
        assertThat(liveCount()).isZero();
        assertThat(archived).hasSize(1);
        assertThat(jdbc.sql("SELECT storage_state FROM evaluation_archive_batch WHERE route_version_id=?")
            .param(route).query(String.class).single()).isEqualTo("PURGED");
    }

    @Test
    void 삭제_설정이_꺼져_있으면_자동_보존이_끝나도_원본을_유지한다() {
        // given
        finish();
        seekCurrent();

        // when
        automatic(false).advance();

        // then
        assertThat(liveCount()).isEqualTo(2);
        assertThat(archived).hasSize(1);
        assertThat(jdbc.sql("SELECT state FROM evaluation_archive_batch WHERE route_version_id=?")
            .param(route).query(String.class).single()).isEqualTo("VERIFIED");
    }

    @Test
    void 재시작한_실행자는_만료된_예약을_인계받아_삭제까지_완료한다() {
        // given
        finish();
        var reserved = store.reserve(route, keys()).orElseThrow();
        jdbc.sql("UPDATE evaluation_archive_batch SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?")
            .param(reserved.id()).update();

        // when
        automatic(true).advance();

        // then
        assertThat(liveCount()).isZero();
        assertThat(location(reserved)).isEqualTo("PURGED");
    }

    @Test
    void 파일_저장이_실패하면_삭제하지_않고_새_후보_탐색도_잠시_멈춘다() {
        // given
        finish();
        seekCurrent();
        var service = new com.gustler.backend.forecasting.application.evaluation.AdvanceEvaluationArchiveService(
            queue, store,
            new com.gustler.backend.forecasting.application.evaluation.ArchiveCompletedEvaluationsService(store,
                (batch, rows) -> { throw new IllegalStateException("시험용 저장 실패"); }),
            new PurgeArchivedEvaluationsService(store, external),
            new com.gustler.backend.forecasting.api.evaluation.EvaluationArchivePolicy(true, 100), clock);

        // when
        service.advance();
        service.advance();

        // then
        assertThat(liveCount()).isEqualTo(2);
        assertThat(queue.available()).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM evaluation_archive_batch WHERE route_version_id=?")
            .param(route).query(Long.class).single()).isOne();
    }

    @Test
    void 탐색_끝에_도달하면_다시_순회하여_늦게_완료된_정산을_발견한다() {
        // given
        finish();
        seekCurrent();
        jdbc.sql("UPDATE forecast_evaluation_result SET scored_at='2026-10-09T00:00:00Z' WHERE vehicle_observation_id=?")
            .param(source).update();
        assertThat(queue.next(100)).isEmpty();
        assertThat(queue.next(100)).isEmpty();
        jdbc.sql("UPDATE forecast_evaluation_result SET scored_at='2026-10-08T11:00:00Z' WHERE vehicle_observation_id=?")
            .param(source).update();

        // when
        boolean found = false;
        for (int i=0; i<100 && !found; i++) {
            found = queue.next(100).map(candidate -> candidate.routeVersionId() == route
                && candidate.keys().contains(new Key(source, 9))).orElse(false);
        }

        // then
        assertThat(found).isTrue();
    }

    @Test
    void 부적격_행이_있어도_지정한_페이지_개수만_검사하고_다음_키로_이어간다() {
        // given
        seekCurrent();

        // when
        assertThat(queue.next(1)).isEmpty();

        // then
        assertThat(jdbc.sql("SELECT stop_order FROM evaluation_archive_scan WHERE id=1").query(Integer.class).single())
            .isEqualTo(9);
        assertThat(queue.next(1)).isEmpty();
        assertThat(jdbc.sql("SELECT stop_order FROM evaluation_archive_scan WHERE id=1").query(Integer.class).single())
            .isEqualTo(10);
    }

    @Test
    void 품질이_바뀐_예약은_원본을_유지한_채_중단하여_새로_예약할_수_있다() {
        // given
        var batch = ready();
        changeQuality();

        // when
        boolean retired = queue.retireChanged(batch);
        var renewed = store.reserve(route, keys()).orElseThrow();

        // then
        assertThat(retired).isTrue();
        assertThat(liveCount()).isEqualTo(2);
        assertThat(renewed.id()).isNotEqualTo(batch.id());
        assertThat(renewed.qualityRevision()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT abandoned_at IS NOT NULL FROM evaluation_archive_batch WHERE id=?")
            .param(batch.id()).query(Boolean.class).single()).isTrue();
        assertThatThrownBy(() -> store.readOwned(batch)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 원본이_없는_예약은_품질이_바뀌어도_재등록_방지_키를_제거하지_않는다() {
        // given
        var batch = ready();
        purge(batch);
        changeQuality();

        // when
        boolean retired = queue.retireChanged(batch);

        // then
        assertThat(retired).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM evaluation_archive_member WHERE batch_id=?")
            .param(batch.id()).query(Long.class).single()).isEqualTo(2);
    }

    private void seekCurrent() {
        jdbc.sql("UPDATE evaluation_archive_scan SET observation_id=?,stop_order=0 WHERE id=1")
            .param(source).update();
    }

    private com.gustler.backend.forecasting.application.evaluation.AdvanceEvaluationArchiveService automatic(boolean delete) {
        return new com.gustler.backend.forecasting.application.evaluation.AdvanceEvaluationArchiveService(queue, store,
            new com.gustler.backend.forecasting.application.evaluation.ArchiveCompletedEvaluationsService(store, (batch, rows) -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                archived.put(batch.id(), rows);
                return new com.gustler.backend.forecasting.application.evaluation.EvaluationArchiveStorage.Verification(
                    "test-only", "a".repeat(64));
            }), new PurgeArchivedEvaluationsService(store, external),
            new com.gustler.backend.forecasting.api.evaluation.EvaluationArchivePolicy(delete, 100), clock);
    }

    private EvaluationArchiveBatch ready() {
        finish();
        return archive();
    }

    private EvaluationArchiveBatch archive() {
        var batch = store.reserve(route, keys()).orElseThrow();
        archived.put(batch.id(), store.readOwned(batch));
        // 실제 객체 검증은 EvaluationArchiveObjectStoreTest에서 확인한다.
        store.recordVerified(batch, "a".repeat(64));
        return batch;
    }

    private List<Key> keys() { return List.of(new Key(source, 9), new Key(source, 10)); }
    private int purge(EvaluationArchiveBatch batch) { return new PurgeArchivedEvaluationsService(store, external).purge(batch); }
    private int restore(EvaluationArchiveBatch batch) { return new RestoreArchivedEvaluationsService(store, external).restore(batch); }

    private void failReceipt() {
        doThrow(new IllegalStateException("시험용 위치 기록 장애")).when(jdbc)
            .sql(contains("UPDATE evaluation_archive_batch SET storage_state="));
    }

    private void insert(Row row) {
        jdbc.sql("INSERT INTO forecast_evaluation_result SELECT * FROM jsonb_populate_record(NULL::forecast_evaluation_result,CAST(? AS jsonb))")
            .param(row.originalJson()).update();
    }

    private void changeQuality() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            quality.lock(route);
            jdbc.sql("UPDATE route_data_quality SET quality_revision=quality_revision+1 WHERE route_id=(SELECT route_id FROM route_version WHERE id=?)")
                .param(route).update();
        });
    }

    private void addSample() {
        jdbc.sql("""
            INSERT INTO stop_demand_pending_sample(route_version_id,prediction_observation_id,arrival_observation_id,
                vehicle_id,target_stop_order,arrived_at,scored_at,prediction_remaining_seats,arrival_remaining_seats)
            SELECT route_version_id,vehicle_observation_id,arrival_observation_id,'archive-bus',target_stop_order,
                arrived_at,scored_at,20,seats_on_arrival FROM forecast_evaluation_result
            WHERE vehicle_observation_id=? AND target_stop_order=9
            """).param(source).update();
    }

    private void settle() {
        long arrival = arrival("2026-10-08T10:10:00Z", 9);
        jdbc.sql("""
            UPDATE forecast_evaluation_result SET scoring_state='SETTLED',arrival_observation_id=:arrival,
                seats_on_arrival=5,arrived_at='2026-10-08T10:10:00Z',arrival_route_version_id=:route,
                arrival_vehicle_id='archive-bus',arrival_stop_order=9,arrival_running_state=2,
                arrival_remaining_seats=5,arrival_quality_direction=0
            WHERE vehicle_observation_id=:source AND target_stop_order=9
            """).param("arrival", arrival).param("route", route).param("source", source).update();
    }

    private long arrival(String at, int stop) {
        long arrivalBatch = jdbc.sql("""
            INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,
                requested_at,response_received_at,outcome,normalization_version,collection_strategy_version)
            VALUES(:route,CAST(:at AS timestamptz),1,:key,CAST(:at AS timestamptz),CAST(:at AS timestamptz),
                'SUCCESS_ROWS','normalization-v1.0.0','adaptive-kst-v1.0.1') RETURNING id
            """).param("route", route).param("at", at).param("key", UUID.randomUUID().toString().substring(0,20))
            .query(Long.class).single();
        return jdbc.sql("""
            INSERT INTO vehicle_observation(observation_batch_id,route_version_id,source_row_number,
                vehicle_id,stop_order,stop_id,passed_stop_order,running_state,remaining_seats)
            VALUES(:batch,:route,0,'archive-bus',:stop,:stopId,:stop,2,5) RETURNING id
            """).param("batch", arrivalBatch).param("route", route).param("stop", stop)
            .param("stopId", "stop-" + stop).query(Long.class).single();
    }

    private void finish() {
        for (int step = 0; step < 100; step++) {
            if (pipeline.step(route).status() == DemandStatisticsPipeline.Step.Status.COMPLETED) return;
        }
        throw new AssertionError("통계가 완료되지 않았다");
    }

    private void rebuild() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            quality.lock(route);
            jdbc.sql("INSERT INTO stop_demand_rebuild_request(route_version_id,vehicle_id,request_id) VALUES(?,'',?)")
                .params(route, UUID.randomUUID()).update();
        });
        finish();
    }

    private List<com.gustler.backend.forecasting.domain.statistics.StopDemandCell> cells() {
        return statistics.readAsOf(route, TimeSlot.EVENING, DemandStatisticsVersion.CURRENT_CALCULATION_VERSION, UNTIL).cells();
    }

    private long liveCount() {
        return jdbc.sql("SELECT count(*) FROM forecast_evaluation_result WHERE route_version_id=?")
            .param(route).query(Long.class).single();
    }

    private String location(EvaluationArchiveBatch batch) {
        return jdbc.sql("SELECT storage_state FROM evaluation_archive_batch WHERE id=?").param(batch.id()).query(String.class).single();
    }
}
