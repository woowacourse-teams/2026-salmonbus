package com.gustler.backend.forecasting.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.forecasting.api.evaluation.EvaluationArchiveObjects;
import com.gustler.backend.forecasting.application.evaluation.AlightingAwareEvaluationArchiveService;
import com.gustler.backend.forecasting.application.RouteWorkAccess;
import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import com.gustler.backend.support.ConfirmedTripFixture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@ForecastingIntegrationTest
class JdbcAlightingAwareForecastEvaluationTest {
    private static final Instant START = Instant.parse("2026-01-05T00:00:00Z");
    private static final String VERSION = "alighting-capacity-snapshot-v2";
    private static final String AFTER = "AFTER_ALIGHTING_ESTIMATE";
    @Autowired
    JdbcClient jdbc;
    @Autowired
    RouteWorkAccess quality;
    @Autowired
    PlatformTransactionManager transactions;
    @Autowired
    JdbcEvaluationWorkStore legacyWork;
    private TransactionTemplate tx;
    private long route;
    private long version;
    private long deployment;
    private long source;
    private long post;
    private JdbcAlightingAwareForecastEvaluation service;
    private final String snapshot = """
        {"capacity":40,"capacityPolicy":"vehicle-capacity-snapshot-v1","realStopOrders":[1,2,3],"alighting":{"stops":[{"stop":2,"band":"morning",
        "p":0.12,"lam":[1.5,1.5,1.5,1.5,1.5,1.5,1.5,1.5],"kappa":1,"r":2}]}}
        """;

    @BeforeEach
    void 정산_자료를_준비한다() {
        tx = new TransactionTemplate(transactions);
        service = new JdbcAlightingAwareForecastEvaluation(jdbc, quality, Clock.systemUTC(), transactions);
        tx.executeWithoutResult(status -> {
            String id = UUID.randomUUID().toString().substring(0, 24);
            route = jdbc.sql("""
                INSERT INTO route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name)
                VALUES(:id,'GBIS',:id,'3330','A','B') RETURNING id
                """).param("id", id).query(Long.class).single();
            version = jdbc
                .sql("INSERT INTO route_version(route_id,content_digest,valid_from) VALUES(?,?,?) RETURNING id")
                .params(route, "0".repeat(64), offset(START)).query(Long.class).single();
            for (int stop = 1; stop <= 3; stop++) {
                jdbc.sql(
                    "INSERT INTO route_stop(route_version_id,stop_order,stop_id,name,direction,boarding_allowed) VALUES(?,?,?,?,?,true)")
                    .params(version, stop, "s" + stop, "정류장" + stop, "UP").update();
            }
            deployment = jdbc.sql("""
                INSERT INTO model_deployment(deployment_key,release_id,model_key,model_version,bundle_digest,
                    prediction_target_version,calculation_version,supported_scope_digest,data_until,state)
                VALUES(?,'alighting-test','alighting','1',?,'ALIGHTING_AWARE_V1','ALIGHTING_AWARE_V1',?,?,'ACTIVE') RETURNING id
                """).params(UUID.randomUUID(), "0".repeat(64), "0".repeat(64), offset(START)).query(Long.class)
                .single();
            observation("previous", -120, 1, 5);
            observation("previous", -100, 2, 0);
            observation("previous", -80, 3, 0);
            source = observation("current", 0, 1, 40);
            observation("current", 20, 1, 2);
            observation("current", 40, 2, 0);
            post = observation("current", 60, 2, 0);
            observation("current", 80, 3, 2);
            final long publication = jdbc
                .sql(
                    """
                        INSERT INTO forecast_publication(source_batch_id,route_version_id,model_deployment_id,
                            demand_statistics_revision,quality_revision,observed_at,generated_at,published_at,prediction_count)
                        SELECT observation_batch_id,route_version_id,?,0,1,?,?,?,1 FROM vehicle_observation WHERE id=? RETURNING id
                        """)
                .params(deployment, offset(START), offset(START.plusSeconds(1)), offset(START.plusSeconds(2)), source)
                .query(Long.class).single();
            jdbc.sql(
                """
                    INSERT INTO seat_forecast(vehicle_observation_id,target_stop_order,route_version_id,stops_to_target,
                        model_deployment_id,demand_statistics_revision,seat_full_chance_raw,seat_full_chance,expected_seats,
                        generated_at,quality_revision,seat_target,probability_target,label_version,feature_snapshot,inference_bundle_hash,publication_id)
                    VALUES(?,2,?,1,?,0,.8,.8,3,?,1,'AFTER_ALIGHTING_ESTIMATE','DEPARTURE_ZERO',?,CAST(? AS jsonb),?,?)
                    """)
                .params(source, version, deployment, offset(START.plusSeconds(1)), VERSION, snapshot, "0".repeat(64),
                    publication)
                .update();
        });
    }

    @AfterEach
    void 준비한_자료를_정리한다() {
        tx.executeWithoutResult(status -> {
            for (String table : List.of("alighting_evaluation_archive", "alighting_forecast_evaluation_result",
                "alighting_forecast_evaluation_work",
                "forecast_evaluation_work", "seat_forecast", "forecast_publication")) {
                jdbc.sql("DELETE FROM " + table + " WHERE route_version_id=?").param(version).update();
            }
            jdbc.sql("DELETE FROM vehicle_observation WHERE route_version_id=?").param(version).update();
            jdbc.sql("DELETE FROM observation_batch WHERE route_version_id=?").param(version).update();
            jdbc.sql("DELETE FROM route_stop WHERE route_version_id=?").param(version).update();
            jdbc.sql("DELETE FROM stop_demand_baseline WHERE route_version_id=?").param(version).update();
            jdbc.sql("DELETE FROM route_version WHERE id=?").param(version).update();
            jdbc.sql("DELETE FROM route WHERE id=?").param(route).update();
            jdbc.sql("DELETE FROM model_deployment WHERE id=?").param(deployment).update();
        });
    }

    @Test
    void 정원이_갱신돼도_예보에_고정한_정원으로_정산한다() {
        // given: 예보 이후 관측 최대 잔여석이 45로 올라가도 예보에 고정된 40을 쓴다.
        jdbc.sql("UPDATE vehicle_observation SET remaining_seats=45 WHERE id=?").param(source).update();

        // when
        service.settle();

        // then
        assertThat(jdbc.sql("""
            SELECT capacity FROM alighting_forecast_evaluation_result
            WHERE vehicle_observation_id=? AND target_kind=? AND scoring_state='SETTLED'
            """).params(source, AFTER).query(Integer.class).single()).isEqualTo(40);
        assertThat(jdbc.sql("""
            SELECT count(*) FROM alighting_forecast_evaluation_result
            WHERE vehicle_observation_id=? AND target_kind=? AND capacity_observation_id IS NOT NULL
            """).params(source, AFTER).query(Integer.class).single()).isZero();
    }

    @Test
    void 발행하면_하차_후_잔여석_정산만_등록한다() {
        assertThat(count("alighting_forecast_evaluation_work")).isOne();
        tx.executeWithoutResult(status -> legacyWork.register(version, List.of(source)));
        assertThat(count("forecast_evaluation_work")).isZero();
        assertThat(count("forecast_evaluation_pending")).isZero();
    }

    @Test
    void 앞_노선의_실패를_롤백하고_뒤_노선은_정산한다() {
        final long failedRoute = route;
        final long failedVersion = version;
        final long failedDeployment = deployment;
        jdbc.sql("UPDATE model_deployment SET state='RETIRED' WHERE id=?").param(deployment).update();
        정산_자료를_준비한다();
        try {
            RouteWorkAccess failing = new RouteWorkAccess() {
                @Override
                public long current(long id) {
                    return quality.current(id);
                }

                @Override
                public long lock(long id) {
                    long revision = quality.lock(id);
                    if (id == failedVersion) {
                        jdbc.sql("UPDATE alighting_forecast_evaluation_work SET reason='temporary' WHERE route_version_id=?")
                            .param(id).update();
                        throw new IllegalStateException("첫 노선 정산 실패");
                    }
                    return revision;
                }

                @Override
                public long lockByRoute(long id) {
                    return quality.lockByRoute(id);
                }
            };
            service = new JdbcAlightingAwareForecastEvaluation(jdbc, failing, Clock.systemUTC(), transactions);

            service.settle();

            assertThat(jdbc.sql("SELECT count(*) FROM alighting_forecast_evaluation_result WHERE route_version_id=?")
                .param(failedVersion).query(Integer.class).single()).isZero();
            assertThat(jdbc.sql("SELECT reason FROM alighting_forecast_evaluation_work WHERE route_version_id=?")
                .param(failedVersion).query(String.class).single()).isEqualTo("AWAITING_OBSERVATION");
            assertThat(count("alighting_forecast_evaluation_result")).isOne();
            assertThat(count("alighting_forecast_evaluation_work")).isZero();
        } finally {
            준비한_자료를_정리한다();
            route = failedRoute;
            version = failedVersion;
            deployment = failedDeployment;
        }
    }

    @Test
    void 같은_편도의_하차_후_잔여석을_소수로_한번만_정산한다() {
        service.settle();
        service.settle();
        assertThat(count("alighting_forecast_evaluation_result")).isOne();
        assertThat(count("alighting_forecast_evaluation_work")).isZero();
        assertThat(label(AFTER)).isBetween(2.0, 40.0).isNotEqualTo(Math.rint(label(AFTER)));
        assertThat(count("forecast_evaluation_result")).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM stop_demand_pending_sample WHERE prediction_observation_id=?")
            .param(source).query(Integer.class).single()).isZero();
    }

    @Test
    void 늦은_계산시각과_관측시각을_구별해_재현한다() {
        jdbc.sql("UPDATE seat_forecast SET generated_at=? WHERE vehicle_observation_id=?")
            .params(offset(START.plusSeconds(172800)), source).update();
        service.settle();
        assertThat(label(AFTER)).isGreaterThan(2);
    }

    @Test
    void 정원_근거가_없으면_하차_후_잔여석_정산은_지원_불가로_남긴다() {
        jdbc.sql("UPDATE seat_forecast SET feature_snapshot=feature_snapshot - 'capacity' WHERE vehicle_observation_id=?").param(source).update();
        service.settle();
        assertThat(jdbc
            .sql("SELECT reason FROM alighting_forecast_evaluation_result WHERE vehicle_observation_id=? AND target_kind=?")
            .params(source, AFTER).query(String.class).single()).isEqualTo("CAPACITY_SNAPSHOT_INVALID");
    }

    @Test
    void 다른_편도_관측을_목표_결과로_연결하지_않는다() {
        jdbc.sql("UPDATE vehicle_observation SET vehicle_trip_key='different-trip' WHERE id=?").param(post).update();
        service.settle();
        assertThat(jdbc.sql(
            "SELECT count(*) FROM alighting_forecast_evaluation_result WHERE route_version_id=? AND scoring_state='SETTLED'")
            .param(version).query(Integer.class).single()).isZero();
        assertThat(count("alighting_forecast_evaluation_result")).isOne();
    }

    @Test
    void 완료한_정산은_후속_회차에서_다시_등록하지_않는다() {
        service.settle();
        final double original = label(AFTER);
        service.settle();
        assertThat(count("alighting_forecast_evaluation_result")).isOne();
        assertThat(count("alighting_forecast_evaluation_work")).isZero();
        assertThat(label(AFTER)).isEqualTo(original);
    }

    @Test
    void 이관_후_원자료가_삭제돼도_같은_라벨을_복원하며_집계가_유지된다() {
        service.settle();
        final double before = label(AFTER);
        jdbc.sql("UPDATE alighting_forecast_evaluation_result SET scored_at=? WHERE route_version_id=?")
            .params(offset(START), version).update();
        MemoryObjects objects = new MemoryObjects();
        AlightingAwareEvaluationArchiveService archive = new AlightingAwareEvaluationArchiveService(
            new JdbcAlightingAwareEvaluationArchiveStore(jdbc, transactions), objects, true, 500);
        archive.advance();
        assertThat(objects.bytes).hasSize(1);
        assertThat(count("alighting_forecast_evaluation_result")).isZero();
        assertThat(count("alighting_forecast_evaluation_metric")).isOne();
        jdbc.sql("DELETE FROM vehicle_observation WHERE id=?").param(post).update();
        assertThat(archive.restore(source, 2, AFTER, VERSION, 1)).isEqualTo(1);
        assertThat(archive.restore(source, 2, AFTER, VERSION, 1)).isZero();
        assertThat(label(AFTER)).isEqualTo(before);
        assertThat(count("alighting_forecast_evaluation_metric")).isOne();
        assertThat(count("alighting_forecast_evaluation_work")).isZero();
        assertThat(
            jdbc.sql("SELECT evidence_archive_hash FROM alighting_forecast_evaluation_result WHERE vehicle_observation_id=?")
                .param(source).query(String.class).single())
            .hasSize(64);
    }

    @Test
    void 원격_검증에_실패하면_Les_원본을_삭제하지_않는다() {
        service.settle();
        EvaluationArchiveObjects corrupted = new EvaluationArchiveObjects() {
            public void putIfAbsent(
                String bucket,
                String key,
                Path source,
                String checksum
            ) {
            }

            public void download(
                String bucket,
                String key,
                Path destination,
                final long limit
            ) {
                try {
                    Files.writeString(destination, "corrupted");
                } catch (IOException error) {
                    throw new IllegalStateException(error);
                }
            }
        };
        jdbc.sql("UPDATE alighting_forecast_evaluation_result SET scored_at=? WHERE route_version_id=?")
            .params(offset(START), version).update();
        AlightingAwareEvaluationArchiveService archive = new AlightingAwareEvaluationArchiveService(
            new JdbcAlightingAwareEvaluationArchiveStore(jdbc, transactions), corrupted, true, 1);
        assertThatThrownBy(archive::advance).isInstanceOf(IllegalStateException.class);
        assertThat(count("alighting_forecast_evaluation_result")).isOne();
        assertThat(count("alighting_evaluation_archive")).isZero();
    }

    @Test
    void 삭제설정을_나중에_켜도_기존_검증_묶음으로_삭제한다() {
        service.settle();
        jdbc.sql("UPDATE alighting_forecast_evaluation_result SET scored_at=? WHERE route_version_id=?")
            .params(offset(START), version).update();
        MemoryObjects objects = new MemoryObjects();
        new AlightingAwareEvaluationArchiveService(new JdbcAlightingAwareEvaluationArchiveStore(jdbc, transactions), objects, false, 500)
            .advance();
        assertThat(count("alighting_forecast_evaluation_result")).isOne();
        new AlightingAwareEvaluationArchiveService(new JdbcAlightingAwareEvaluationArchiveStore(jdbc, transactions), objects, true, 1)
            .advance();
        assertThat(count("alighting_forecast_evaluation_result")).isZero();
        assertThat(objects.bytes).hasSize(1);
    }

    @Test
    void 선행차량이_나중에_확정되면_라벨의_확정시각도_늦춘다() {
        tx.executeWithoutResult(status -> {
            jdbc.sql(
                "UPDATE vehicle_observation SET passed_stop_order=2,stop_order=2,stop_id='s2' WHERE route_version_id=? AND vehicle_id='previous' AND passed_stop_order=3")
                .param(version).update();
            observation("previous", 0, 2, 0);
            observation("previous", 60, 2, 0);
            observation("previous", 120, 2, 0);
            observation("previous", 180, 3, 0);
        });
        service.settle();
        assertThat(jdbc
            .sql("SELECT known_at FROM alighting_forecast_evaluation_result WHERE vehicle_observation_id=? AND target_kind=?")
            .params(source, AFTER).query(OffsetDateTime.class).single().toInstant()).isEqualTo(START.plusSeconds(180));
    }

    @Test
    void 조회_상한_초과는_관측실패가_아닌_지원불가로_기록하고_설정한도를_적용한다() {
        new JdbcAlightingAwareForecastEvaluation(jdbc, quality, Clock.systemUTC(), transactions, 7).settle();
        assertThat(
            jdbc.sql("SELECT DISTINCT scoring_state||':'||reason FROM alighting_evaluation_status WHERE route_version_id=?")
                .param(version).query(String.class).list())
            .containsExactly("UNSUPPORTED:OBSERVATION_INPUT_LIMIT");
        assertThat(count("alighting_forecast_evaluation_work")).isZero();
        jdbc.sql("DELETE FROM alighting_forecast_evaluation_result WHERE vehicle_observation_id=?").param(source).update();
        jdbc.sql("""
            INSERT INTO alighting_forecast_evaluation_work(vehicle_observation_id,target_stop_order,target_kind,label_version,route_version_id)
            VALUES(?,2,'AFTER_ALIGHTING_ESTIMATE',?,?)
            """).params(source, VERSION, version).update();
        new JdbcAlightingAwareForecastEvaluation(jdbc, quality, Clock.systemUTC(), transactions, 8).settle();
        assertThat(jdbc
            .sql(
                "SELECT count(*) FROM alighting_quality_eligible_metric WHERE route_version_id=? AND scoring_state='SETTLED'")
            .param(version).query(Integer.class).single()).isOne();
    }

    @Test
    void 원자료_부분삭제는_지원불가로_구별하고_보관뒤에도_이유와_상태를_조회한다() {
        jdbc.sql("DELETE FROM vehicle_observation WHERE id=?").param(post).update();
        service.settle();
        assertThat(
            jdbc.sql("SELECT DISTINCT scoring_state||':'||reason FROM alighting_evaluation_status WHERE route_version_id=?")
                .param(version).query(String.class).list())
            .containsExactly("UNSUPPORTED:SOURCE_HISTORY_INCOMPLETE");
        jdbc.sql("UPDATE alighting_forecast_evaluation_result SET scored_at=? WHERE route_version_id=?")
            .params(offset(START), version).update();
        new AlightingAwareEvaluationArchiveService(new JdbcAlightingAwareEvaluationArchiveStore(jdbc, transactions), new MemoryObjects(),
            true, 500).advance();
        assertThat(
            jdbc.sql("SELECT DISTINCT reason||':'||retention_state FROM alighting_evaluation_status WHERE route_version_id=?")
                .param(version).query(String.class).list())
            .containsExactly("SOURCE_HISTORY_INCOMPLETE:ARCHIVED");
    }

    @Test
    void 수집건수_기록이_없으면_부분삭제라고_단정하지_않고_검증불가로_구분한다() {
        jdbc.sql(
            "UPDATE observation_batch SET stored_rows=NULL WHERE id=(SELECT observation_batch_id FROM vehicle_observation WHERE id=?)")
            .param(source).update();
        service.settle();
        assertThat(jdbc.sql("SELECT DISTINCT reason FROM alighting_evaluation_status WHERE route_version_id=?")
            .param(version).query(String.class).list()).containsExactly("SOURCE_HISTORY_UNVERIFIED");
    }

    @Test
    void 처리_예산이_허용하면_백개를_넘는_정산도_한_회차에서_처리한다() {
        // given
        jdbc.sql("""
            INSERT INTO alighting_forecast_evaluation_work(vehicle_observation_id,target_stop_order,target_kind,
                label_version,route_version_id)
            SELECT vehicle_observation_id,target_stop_order,seat_target,'unknown-'||n,route_version_id
            FROM seat_forecast CROSS JOIN generate_series(1,250) n WHERE vehicle_observation_id=?
            """).param(source).update();

        // when
        new JdbcAlightingAwareForecastEvaluation(jdbc, quality, Clock.systemUTC(), transactions, 50_000, 150_000, 220).settle();

        // then
        assertThat(count("alighting_forecast_evaluation_work")).isEqualTo(31);
        assertThat(count("alighting_forecast_evaluation_result")).isEqualTo(220);
    }

    @Test
    void 다음날_관측이_늘어도_확인시각_정산의_입력_범위를_넓히지_않는다() {
        // given
        jdbc.sql("UPDATE observation_batch SET ingestion_origin='LIVE',input_confirmed_at=response_received_at "
            + "WHERE route_version_id=?").param(version).update();
        jdbc.sql("""
            UPDATE seat_forecast SET label_version='alighting-confirmed-capacity-snapshot-v3',feature_snapshot=feature_snapshot ||
                '{"inputProfile":"alighting-confirmed-closure-v2",
                  "capacityPolicy":"vehicle-capacity-snapshot-v1","turn":4}'::jsonb ||
                jsonb_build_object('capacityObservationId',vehicle_observation_id,'capacity',40)
            WHERE vehicle_observation_id=?
            """).param(source).update();
        jdbc.sql("UPDATE alighting_forecast_evaluation_work SET label_version='alighting-confirmed-capacity-snapshot-v3' "
            + "WHERE vehicle_observation_id=?").param(source).update();
        observation("unrelated", 86400, 1, 40);
        jdbc.sql("UPDATE observation_batch SET ingestion_origin='LIVE',input_confirmed_at=response_received_at "
            + "WHERE route_version_id=?").param(version).update();

        // when
        new JdbcAlightingAwareForecastEvaluation(jdbc, quality, Clock.systemUTC(), transactions, 50_000, 8).settle();

        // then
        assertThat(jdbc.sql("SELECT scoring_state,reason FROM alighting_forecast_evaluation_result "
            + "WHERE vehicle_observation_id=? AND target_kind=?")
            .params(source, AFTER).query().singleRow()).containsEntry("scoring_state", "SETTLED");
    }

    @Test
    void 근거가_바뀐_보관_묶음은_격리하고_다음_후보를_이관한다() {
        // given
        final long second = observation("second", 10, 1, 40);
        copyForecast(second);
        service.settle();
        jdbc.sql("UPDATE alighting_forecast_evaluation_result SET scored_at=? WHERE route_version_id=?")
            .params(offset(START), version).update();
        MemoryObjects objects = new MemoryObjects();
        JdbcAlightingAwareEvaluationArchiveStore store = new JdbcAlightingAwareEvaluationArchiveStore(jdbc, transactions);
        new AlightingAwareEvaluationArchiveService(store, objects, false, 1).advance();
        jdbc.sql("UPDATE vehicle_observation SET remaining_seats=1 WHERE id=?").param(post).update();
        AlightingAwareEvaluationArchiveService archive = new AlightingAwareEvaluationArchiveService(store, objects, true, 1);

        // when
        archive.advance();
        archive.advance();

        // then
        assertThat(jdbc.sql("SELECT count(*) FROM alighting_evaluation_archive "
            + "WHERE route_version_id=? AND abandoned_at IS NOT NULL")
            .param(version).query(Integer.class).single()).isEqualTo(1);
        assertThat(count("alighting_forecast_evaluation_result")).isEqualTo(1);
        assertThat(store.verifiedForPurge()).isEmpty();
    }

    @Test
    void 일부_결과를_먼저_삭제해도_남은_결과는_재평가_후_이관한다() {
        // given
        final long second = observation("second", 10, 1, 40);
        copyForecast(second);
        service.settle();
        jdbc.sql("UPDATE alighting_forecast_evaluation_result SET scored_at=? WHERE route_version_id=?")
            .params(offset(START), version).update();
        MemoryObjects objects = new MemoryObjects();
        JdbcAlightingAwareEvaluationArchiveStore store = new JdbcAlightingAwareEvaluationArchiveStore(jdbc, transactions);
        new AlightingAwareEvaluationArchiveService(store, objects, false, 500).advance();
        jdbc.sql("""
            INSERT INTO alighting_forecast_evaluation_work(vehicle_observation_id,target_stop_order,target_kind,
                label_version,route_version_id) VALUES(?,2,'AFTER_ALIGHTING_ESTIMATE',?,?)
            """).params(source, VERSION, version).update();
        AlightingAwareEvaluationArchiveService archive = new AlightingAwareEvaluationArchiveService(store, objects, true, 500);
        archive.advance();
        assertThat(count("alighting_forecast_evaluation_result")).isOne();

        // when
        archive.advance();
        jdbc.sql("DELETE FROM alighting_forecast_evaluation_work WHERE route_version_id=?").param(version).update();
        archive.advance();

        // then
        assertThat(count("alighting_forecast_evaluation_result")).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM alighting_evaluation_archive "
            + "WHERE route_version_id=? AND storage_state='PURGED' AND abandoned_at IS NULL")
            .param(version).query(Integer.class).single()).isEqualTo(2);
        assertThat(objects.bytes).hasSize(1);
    }

    @Test
    void 지연된_여러_예보를_처리해도_관측_목록이_힙에_계속_쌓이지_않는다() {
        // given
        PlatformTransactionManager heapTransactions = new HeapTestTransactions(transactions);
        useConfirmedProfile();
        for (int seconds = 1; seconds <= 24; seconds++) {
            final long next = observation("late-" + seconds, seconds, 1, 40);
            copyForecast(next);
        }
        final long noise = observation("noise-first", -60, 1, 40);
        jdbc.sql("""
            INSERT INTO vehicle_observation(observation_batch_id,route_version_id,source_row_number,vehicle_id,
                stop_order,stop_id,passed_stop_order,running_state,remaining_seats)
            SELECT observation_batch_id,route_version_id,n,'noise-'||n,1,'s1',1,2,40
            FROM vehicle_observation CROSS JOIN generate_series(1,50000) n WHERE id=?
            """).param(noise).update();
        jdbc.sql("""
            UPDATE observation_batch b SET ingestion_origin='LIVE',input_confirmed_at=response_received_at,
                stored_rows=(SELECT count(*) FROM vehicle_observation o WHERE o.observation_batch_id=b.id)
            WHERE route_version_id=?
            """).param(version).update();

        // when
        new JdbcAlightingAwareForecastEvaluation(jdbc, quality, Clock.systemUTC(), heapTransactions).settle();

        // then
        assertThat(count("alighting_forecast_evaluation_work")).isZero();
        assertThat(count("alighting_forecast_evaluation_result")).isEqualTo(25);
        assertThat(jdbc.sql("SELECT DISTINCT reason FROM alighting_forecast_evaluation_result "
            + "WHERE route_version_id=? AND vehicle_observation_id<>?")
            .params(version, source).query(String.class).list()).containsExactly("NO_CLOSED_IDENTITY_SAFE_PASS");
    }

    private void useConfirmedProfile() {
        jdbc.sql("""
            UPDATE seat_forecast SET label_version='alighting-confirmed-capacity-snapshot-v3',feature_snapshot=feature_snapshot ||
                '{"inputProfile":"alighting-confirmed-closure-v2",
                  "capacityPolicy":"vehicle-capacity-snapshot-v1","turn":4}'::jsonb
            WHERE vehicle_observation_id=?
            """).param(source).update();
        jdbc.sql("UPDATE alighting_forecast_evaluation_work SET label_version='alighting-confirmed-capacity-snapshot-v3' "
            + "WHERE vehicle_observation_id=?").param(source).update();
    }

    private void copyForecast(final long observationId) {
        jdbc.sql("""
            INSERT INTO seat_forecast
            SELECT (jsonb_populate_record(NULL::seat_forecast,
                to_jsonb(f)||jsonb_build_object('vehicle_observation_id',:id))).*
            FROM seat_forecast f WHERE vehicle_observation_id=:source
            """).param("id", observationId).param("source", source).update();
    }

    private long observation(
        String vehicle,
        final int seconds,
        final int stop,
        final int seats
    ) {
        OffsetDateTime time = offset(START.plusSeconds(seconds));
        final long batch = jdbc
            .sql(
                """
                    INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,requested_at,response_received_at,
                        outcome,normalization_version,collection_strategy_version,stored_rows)
                    VALUES(?,?,1,?,?,?,'SUCCESS_ROWS','normalization-v1.0.0','adaptive-kst-v1.0.1',1) RETURNING id
                    """)
            .params(version, time, UUID.randomUUID().toString(), time, time).query(Long.class).single();
        final long id = jdbc
            .sql(
                """
                    INSERT INTO vehicle_observation(observation_batch_id,route_version_id,source_row_number,vehicle_id,stop_order,stop_id,
                        passed_stop_order,running_state,remaining_seats) VALUES(?,?,0,?,?,?,?,2,?) RETURNING id
                    """)
            .params(batch, version, vehicle, stop, "s" + stop, stop, seats).query(Long.class).single();
        return ConfirmedTripFixture.include(jdbc, id);
    }

    private int count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE route_version_id=?").param(version)
            .query(Integer.class).single();
    }

    private double label(String target) {
        return jdbc.sql(
            "SELECT label_value FROM alighting_forecast_evaluation_result WHERE vehicle_observation_id=? AND target_kind=?")
            .params(source, target).query(Double.class).single();
    }

    private static OffsetDateTime offset(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    /** 힙 회귀 검사는 CI 처리 속도와 분리한다. 실제 DB 트랜잭션에만 120초를 주고 힙 제한은 유지한다. */
    private record HeapTestTransactions(PlatformTransactionManager delegate) implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            DefaultTransactionDefinition heapCheck = new DefaultTransactionDefinition(definition);
            heapCheck.setTimeout(120);
            return delegate.getTransaction(heapCheck);
        }

        @Override
        public void commit(TransactionStatus status) {
            delegate.commit(status);
        }

        @Override
        public void rollback(TransactionStatus status) {
            delegate.rollback(status);
        }
    }

    private static final class MemoryObjects implements EvaluationArchiveObjects {
        private final Map<String, byte[]> bytes = new HashMap<>();

        public void putIfAbsent(
            String bucket,
            String key,
            Path source,
            String checksum
        ) {
            try {
                bytes.putIfAbsent(key, Files.readAllBytes(source));
            } catch (IOException error) {
                throw new IllegalStateException(error);
            }
        }

        public void download(
            String bucket,
            String key,
            Path destination,
            final long limit
        ) {
            try {
                Files.write(destination, bytes.get(key), StandardOpenOption.CREATE_NEW);
            } catch (IOException error) {
                throw new IllegalStateException(error);
            }
        }
    }
}
