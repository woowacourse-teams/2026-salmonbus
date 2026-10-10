package com.gustler.backend.forecasting.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch.Key;
import com.gustler.backend.forecasting.domain.evaluation.ForecastEvaluation;
import com.gustler.backend.forecasting.domain.evaluation.ForecastEvaluationRepository;
import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 바깥 테스트 트랜잭션 없이 제품의 실제 커밋·롤백을 검증한다. */
@ForecastingIntegrationTest
@org.springframework.test.context.TestPropertySource(properties = "sal175.archive-store-test=true")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class JdbcEvaluationArchiveStoreTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-09T00:00:00Z"), ZoneId.of("Asia/Seoul"));
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired RouteDataQualityAccess quality;
    @Autowired ForecastEvaluationRepository evaluations;
    private JdbcEvaluationArchiveStore store;
    private long route;
    private long source;

    @BeforeEach
    void 서로_독립된_노선의_과거_정산을_준비한다() {
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
    void 이관_예약한_완료_정산은_원본이_없어도_다시_대기로_생성되지_않는다() {
        // given
        reserve(9);
        jdbc.sql("DELETE FROM forecast_evaluation_result WHERE vehicle_observation_id=? AND target_stop_order=9")
            .param(source).update();

        // when
        addPending(9);

        // then
        assertThat(pendingCount()).isZero();
    }

    @Test
    void 이관되지_않은_새_예보는_정산_대기로_등록된다() {
        // given
        reserve(9);

        // when
        addPending(11);

        // then
        assertThat(pendingCount()).isOne();
    }

    @Test
    void 먼저_지나간_키의_정산도_늦게_완료되면_별도로_예약할_수_있다() {
        // given
        jdbc.sql("UPDATE forecast_evaluation_result SET scored_at='2026-10-09T00:00:00Z' WHERE vehicle_observation_id=? AND target_stop_order=9")
            .param(source).update();
        assertThat(store.reserve(route, List.of(new Key(source, 9)))).isEmpty();
        reserve(10);
        var tomorrow = new JdbcEvaluationArchiveStore(jdbc, quality,
            Clock.fixed(Instant.parse("2026-10-10T00:00:00Z"), CLOCK.getZone()), transactions);

        // when
        var late = tomorrow.reserve(route, List.of(new Key(source, 9)));

        // then
        assertThat(late).isPresent();
        assertThat(tomorrow.readOwned(late.orElseThrow())).extracting(row -> row.key().stopOrder()).containsExactly(9);
    }

    @Test
    void 다른_작업자가_예약한_정산을_중복으로_예약하지_않는다() {
        // given
        reserve(9);
        var another = new JdbcEvaluationArchiveStore(jdbc, quality, CLOCK, transactions);

        // when
        var duplicate = another.reserve(route, List.of(new Key(source, 9)));

        // then
        assertThat(duplicate).isEmpty();
        assertThat(batchCount()).isOne();
    }

    @Test
    void 만료된_작업을_이어받은_뒤에는_이전_작업자가_상태를_확정하지_못한다() {
        // given
        var original = reserve(9);
        jdbc.sql("UPDATE evaluation_archive_batch SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?")
            .param(original.id()).update();

        // when
        var resumed = store.reclaim(original.id()).orElseThrow();

        // then
        assertThat(resumed.leaseToken()).isNotEqualTo(original.leaseToken());
        assertThatThrownBy(() -> store.recordVerified(original, "a".repeat(64))).isInstanceOf(IllegalStateException.class);
        store.recordVerified(resumed, "a".repeat(64));
        assertThat(state(original)).isEqualTo("VERIFIED");
    }

    @Test
    void 아직_유효한_작업의_소유권을_다른_실행자가_가져가지_못한다() {
        // given
        var batch = reserve(9);

        // when
        var reclaimed = store.reclaim(batch.id());

        // then
        assertThat(reclaimed).isEmpty();
        assertThat(store.readOwned(batch)).hasSize(1);
    }

    @Test
    void 서울_자정에_완료된_정산은_오늘_자료로_남긴다() {
        // given
        jdbc.sql("UPDATE forecast_evaluation_result SET scored_at='2026-10-08T15:00:00Z' WHERE vehicle_observation_id=?")
            .param(source).update();

        // when
        var batch = store.reserve(route, List.of(new Key(source, 9), new Key(source, 10)));

        // then
        assertThat(batch).isEmpty();
        assertThat(batchCount()).isZero();
    }

    @Test
    void 도착_시각이_오늘인_정산은_완료_시각과_별개로_보호한다() {
        // given: 시간 순서가 잘못된 원본도 현재 날짜 도착 보호 조건으로 거부한다.
        jdbc.sql("""
            UPDATE forecast_evaluation_result SET scoring_state='SETTLED',arrival_observation_id=:source,
                seats_on_arrival=20,arrived_at='2026-10-08T15:00:00Z',arrival_route_version_id=:route,
                arrival_vehicle_id='archive-bus',arrival_stop_order=8,arrival_running_state=2,
                arrival_remaining_seats=20,arrival_quality_direction=1
            WHERE vehicle_observation_id=:source AND target_stop_order=9
            """).param("source", source).param("route", route).update();

        // when
        var batch = store.reserve(route, List.of(new Key(source, 9)));

        // then
        assertThat(batch).isEmpty();
    }

    @Test
    void 다른_노선의_키를_전달해도_현재_노선의_이관_묶음에_넣지_않는다() {
        // given
        String key = UUID.randomUUID().toString().substring(0, 20);
        long otherRouteId = jdbc.sql("""
            INSERT INTO route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name)
            VALUES(:key,'GBIS',:key,'다른 노선','출발','도착') RETURNING id
            """).param("key", key).query(Long.class).single();
        long otherRoute = jdbc.sql("""
            INSERT INTO route_version(route_id,content_digest,valid_from)
            VALUES(:route,:digest,'2026-10-02T00:00:00Z') RETURNING id
            """).param("digest", "1".repeat(64)).param("route", otherRouteId).query(Long.class).single();

        // when
        var batch = store.reserve(otherRoute, List.of(new Key(source, 9)));

        // then
        assertThat(batch).isEmpty();
    }

    @Test
    void 노선_잠금을_얻지_못하면_일부_예약도_남기지_않는다() throws Exception {
        // given
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var owner = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                quality.lock(route);
                locked.countDown();
                try {
                    if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new IllegalStateException("테스트 잠금 해제 시간 초과");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            try {
                assertThat(locked.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

                // when & then
                assertThatThrownBy(() -> reserve(9)).isInstanceOf(org.springframework.dao.DataAccessException.class);
                assertThat(batchCount()).isZero();
            } finally {
                release.countDown();
            }
            owner.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(reserve(9).rowCount()).isOne();
    }

    @Test
    void 추출_후_원본이_바뀌면_검증_완료로_기록하지_않는다() {
        // given
        var batch = reserve(9);
        jdbc.sql("UPDATE forecast_evaluation_result SET scoring_state='SKIPPED' WHERE vehicle_observation_id=? AND target_stop_order=9")
            .param(source).update();

        // when & then
        assertThatThrownBy(() -> store.recordVerified(batch, "a".repeat(64))).isInstanceOf(IllegalStateException.class);
        assertThat(state(batch)).isEqualTo("RESERVED");
    }

    @Test
    void 예약한_정산이_없어지면_빈_자료를_검증_완료로_기록하지_않는다() {
        // given
        var batch = reserve(9);
        jdbc.sql("DELETE FROM forecast_evaluation_result WHERE vehicle_observation_id=? AND target_stop_order=9")
            .param(source).update();

        // when & then
        assertThatThrownBy(() -> store.recordVerified(batch, "a".repeat(64))).isInstanceOf(IllegalStateException.class);
        assertThat(state(batch)).isEqualTo("RESERVED");
    }

    @Test
    void 품질_판본이_바뀌면_이전_예약의_검증_결과를_확정하지_않는다() {
        // given
        var batch = reserve(9);
        jdbc.sql("UPDATE route_data_quality SET quality_revision=quality_revision+1 WHERE route_id=(SELECT route_id FROM route_version WHERE id=?)")
            .param(route).update();

        // when & then
        assertThatThrownBy(() -> store.recordVerified(batch, "a".repeat(64))).isInstanceOf(IllegalStateException.class);
        assertThat(state(batch)).isEqualTo("RESERVED");
    }

    @Test
    void 같은_검증_결과는_재시도할_수_있지만_다른_파일로_바꾸지_못한다() {
        // given
        var batch = reserve(9);
        store.recordVerified(batch, "a".repeat(64));

        // when
        store.recordVerified(batch, "a".repeat(64));

        // then
        assertThat(state(batch)).isEqualTo("VERIFIED");
        assertThatThrownBy(() -> store.recordVerified(batch, "b".repeat(64))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 키_기록에_실패하면_빈_이관_묶음도_남기지_않는다() {
        // given
        JdbcClient failing = spy(jdbc);
        doThrow(new DataIntegrityViolationException("실패 주입")).when(failing).sql(contains("INSERT INTO evaluation_archive_member"));
        var broken = new JdbcEvaluationArchiveStore(failing, quality, CLOCK, transactions);

        // when & then
        assertThatThrownBy(() -> broken.reserve(route, List.of(new Key(source, 9))))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(batchCount()).isZero();
        assertThat(reserve(9).rowCount()).isOne();
    }

    @Test
    void 호출자가_열어둔_트랜잭션에_이관_작업을_포함하지_않는다() {
        // given
        var transaction = new TransactionTemplate(transactions);

        // when & then
        assertThatThrownBy(() -> transaction.execute(status -> reserve(9)))
            .isInstanceOf(IllegalStateException.class);
        assertThat(batchCount()).isZero();
    }

    private EvaluationArchiveBatch reserve(int stop) {
        return store.reserve(route, List.of(new Key(source, stop))).orElseThrow();
    }

    @Test
    void 외부_전송이_실패하면_검증_완료로_기록하지_않는다() {
        // given
        var batch = reserve(9);
        var service = new com.gustler.backend.forecasting.application.evaluation.ArchiveCompletedEvaluationsService(
            store, (reserved, rows) -> { throw new IllegalStateException("전송 실패 주입"); });

        // when & then
        assertThatThrownBy(() -> service.archive(batch)).isInstanceOf(IllegalStateException.class);
        assertThat(state(batch)).isEqualTo("RESERVED");
        assertThat(store.readOwned(batch)).hasSize(1);
    }

    @Test
    void 외부_전송_중에는_트랜잭션을_종료하고_원본을_그대로_유지한다() {
        // given
        var batch = reserve(9);
        var service = new com.gustler.backend.forecasting.application.evaluation.ArchiveCompletedEvaluationsService(
            store, (reserved, rows) -> {
                assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(rows).hasSize(1);
                return new com.gustler.backend.forecasting.application.evaluation.EvaluationArchiveStorage.Verification(
                    "s3://test-only", "a".repeat(64));
            });

        // when
        service.archive(batch);

        // then
        assertThat(state(batch)).isEqualTo("VERIFIED");
        assertThat(store.readOwned(batch)).hasSize(1);
    }

    @Test
    void 전송_중_원본이_바뀌면_파일_검증에_성공했어도_확정하지_않는다() {
        // given
        var batch = reserve(9);
        var service = new com.gustler.backend.forecasting.application.evaluation.ArchiveCompletedEvaluationsService(
            store, (reserved, rows) -> {
                jdbc.sql("UPDATE forecast_evaluation_result SET scoring_state='SKIPPED' WHERE vehicle_observation_id=? AND target_stop_order=9")
                    .param(source).update();
                return new com.gustler.backend.forecasting.application.evaluation.EvaluationArchiveStorage.Verification(
                    "s3://test-only", "a".repeat(64));
            });

        // when & then
        assertThatThrownBy(() -> service.archive(batch)).isInstanceOf(IllegalStateException.class);
        assertThat(state(batch)).isEqualTo("RESERVED");
    }

    private void addPending(int stop) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            quality.lock(route);
            evaluations.addPending(route, List.of(ForecastEvaluation.pending(source, stop)));
        });
    }

    private long pendingCount() {
        return jdbc.sql("SELECT count(*) FROM forecast_evaluation_pending WHERE route_version_id=?")
            .param(route).query(Long.class).single();
    }

    private long batchCount() {
        return jdbc.sql("SELECT count(*) FROM evaluation_archive_batch WHERE route_version_id=?")
            .param(route).query(Long.class).single();
    }

    private String state(EvaluationArchiveBatch batch) {
        return jdbc.sql("SELECT state FROM evaluation_archive_batch WHERE id=?").param(batch.id()).query(String.class).single();
    }
}
