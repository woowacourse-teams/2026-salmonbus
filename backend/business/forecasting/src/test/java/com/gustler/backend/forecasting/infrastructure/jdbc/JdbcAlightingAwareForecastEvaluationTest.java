package com.gustler.backend.forecasting.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.gustler.backend.forecasting.api.evaluation.EvaluationArchiveObjects;
import com.gustler.backend.forecasting.application.RouteWorkAccess;
import com.gustler.backend.forecasting.application.evaluation.AlightingAwareEvaluationArchiveService;
import com.gustler.backend.forecasting.application.publication.ForecastBatchCommitter;
import com.gustler.backend.forecasting.domain.publication.ForecastPublication;
import com.gustler.backend.forecasting.domain.publication.PendingForecastBatch;
import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import com.gustler.backend.support.ConfirmedTripFixture;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
    ForecastBatchCommitter committer;
    @Autowired
    PlatformTransactionManager transactions;
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
        assertThat(count("forecast_evaluation_work")).isZero();
        assertThat(count("forecast_evaluation_pending")).isZero();
    }

    @Test
    void 정산_조회_중에도_발행과_노선_갱신이_진행되고_이전_판본의_결과는_저장하지_않는다() {
        // given
        useConfirmedProfile();
        final long publicationSource = observation("publication", 120, 1, 40);
        final long publicationBatch = jdbc.sql("SELECT observation_batch_id FROM vehicle_observation WHERE id=?")
            .param(publicationSource).query(Long.class).single();
        Instant observedAt = START.plusSeconds(120);
        Instant generatedAt = observedAt.plusSeconds(1);
        JdbcClient observing = mock(JdbcClient.class, delegatesTo(jdbc));
        TransactionTemplate writer = new TransactionTemplate(transactions);
        writer.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        doAnswer(call -> {
            writer.executeWithoutResult(status -> {
                jdbc.sql("SET LOCAL lock_timeout='500ms'").update();
                committer.commit(new PendingForecastBatch(publicationBatch, version, route, observedAt),
                    new ForecastPublication(publicationBatch, version, deployment, 0, 1,
                        observedAt, generatedAt, generatedAt, List.of()));
                jdbc.sql("UPDATE route SET data_revision=data_revision+1 WHERE id=?").param(route).update();
            });
            return jdbc.sql(call.getArgument(0, String.class));
        }).when(observing).sql(contains("WITH requested AS"));

        // when
        new JdbcAlightingAwareForecastEvaluation(observing, quality, Clock.systemUTC(), transactions).settle();

        // then
        assertSoftly(softly -> {
            softly.assertThat(quality.current(version)).isEqualTo(2);
            softly.assertThat(count("forecast_publication")).isEqualTo(2);
            softly.assertThat(count("alighting_forecast_evaluation_result")).isZero();
            softly.assertThat(count("alighting_forecast_evaluation_work")).isOne();
        });
        service.settle();
        assertThat(jdbc.sql("""
            SELECT quality_revision
            FROM alighting_forecast_evaluation_result
            WHERE vehicle_observation_id = ?
            """).param(source).query(Long.class).single()).isEqualTo(2);
    }

    @ParameterizedTest
    @CsvSource({"true, 1, 0", "false, 0, 1"})
    void 같은_페이지가_먼저_완료되거나_재예약되면_이전_계산을_저장하지_않는다(
        final boolean anotherSettlement,
        final int expectedResults,
        final int expectedPending
    ) {
        // given
        useConfirmedProfile();
        JdbcClient observing = mock(JdbcClient.class, delegatesTo(jdbc));
        try (ExecutorService writer = Executors.newSingleThreadExecutor()) {
            doAnswer(call -> {
                writer.submit(() -> {
                    if (anotherSettlement) {
                        service.settle();
                        return;
                    }
                    jdbc.sql("""
                        UPDATE alighting_forecast_evaluation_work
                        SET next_attempt_at = clock_timestamp() + interval '1 hour'
                        WHERE vehicle_observation_id = ?
                        """).param(source).update();
                }).get(5, TimeUnit.SECONDS);
                return jdbc.sql(call.getArgument(0, String.class));
            }).when(observing).sql(contains("WITH requested AS"));

            // when
            new JdbcAlightingAwareForecastEvaluation(observing, quality, Clock.systemUTC(), transactions).settle();
        }

        // then
        assertSoftly(softly -> {
            softly.assertThat(count("alighting_forecast_evaluation_result")).isEqualTo(expectedResults);
            softly.assertThat(count("alighting_forecast_evaluation_work")).isEqualTo(expectedPending);
        });
        jdbc.sql("""
            UPDATE alighting_forecast_evaluation_work
            SET next_attempt_at = clock_timestamp()
            WHERE vehicle_observation_id = ?
            """).param(source).update();
        service.settle();
        assertThat(count("alighting_forecast_evaluation_result")).isOne();
        assertThat(count("alighting_forecast_evaluation_work")).isZero();
    }

    @Test
    void 저장_전_발행_입력이_바뀌면_준비한_결과를_저장하지_않는다() {
        useConfirmedProfile();
        JdbcClient observing = mock(JdbcClient.class, delegatesTo(jdbc));
        TransactionTemplate republish = new TransactionTemplate(transactions);
        republish.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        doAnswer(call -> {
            republish.executeWithoutResult(status -> jdbc.sql("""
                UPDATE seat_forecast SET feature_snapshot=feature_snapshot || '{"reissued":true}'::jsonb
                WHERE vehicle_observation_id=?
                """).param(source).update());
            return jdbc.sql(call.getArgument(0, String.class));
        }).when(observing).sql(contains("WITH requested AS"));

        new JdbcAlightingAwareForecastEvaluation(observing, quality, Clock.systemUTC(), transactions).settle();

        assertThat(count("alighting_forecast_evaluation_result")).isZero();
        assertThat(count("alighting_forecast_evaluation_work")).isOne();
        service.settle();
        assertThat(count("alighting_forecast_evaluation_result")).isOne();
        assertThat(count("alighting_forecast_evaluation_work")).isZero();
    }

    @ParameterizedTest
    @CsvSource({"true, false, 1", "false, false, 1", "false, true, 2"})
    void 잠기지_않고_계산_범위가_그대로인_정산은_처리한다(
        final boolean confirmed,
        final boolean lockLatest,
        final int expectedPending
    ) throws Exception {
        // given
        if (confirmed) {
            useConfirmedProfile();
        }
        final long next = observation("next", 120, 1, 40);
        copyForecast(next);
        long lockedObservation = source;
        List<Long> expectedSettled = List.of(next);
        if (lockLatest) {
            lockedObservation = next;
            expectedSettled = List.of();
        }
        final long lockedObservationId = lockedObservation;
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
            Future<?> holding = worker.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.sql("""
                    SELECT vehicle_observation_id FROM alighting_forecast_evaluation_work
                    WHERE vehicle_observation_id=? FOR UPDATE
                    """).param(lockedObservationId).query(Long.class).single();
                locked.countDown();
                try {
                    assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

                // when
                service.settle();

                // then
                assertThat(jdbc.sql("""
                    SELECT vehicle_observation_id
                    FROM alighting_forecast_evaluation_result
                    WHERE route_version_id = ?
                    """).param(version).query(Long.class).list()).containsExactlyElementsOf(expectedSettled);
                assertThat(count("alighting_forecast_evaluation_work")).isEqualTo(expectedPending);
            } finally {
                release.countDown();
            }
            holding.get(5, TimeUnit.SECONDS);
        }
        service.settle();
        assertThat(count("alighting_forecast_evaluation_work")).isZero();
        assertThat(count("alighting_forecast_evaluation_result")).isEqualTo(2);
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
        assertThat(jdbc.sql("SELECT to_regclass('stop_demand_pending_sample') IS NULL").query(Boolean.class).single()).isTrue();
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
    void 옛_판본의_이관_파일은_키와_바이트를_바꾸지_않고_반복_복원한다() throws Exception {
        service.settle();
        final double before = label(AFTER);
        String historicalVersion = "les-identity-safe-v1";
        jdbc.sql("UPDATE alighting_forecast_evaluation_result SET label_version=?,scored_at=? WHERE route_version_id=?")
            .params(historicalVersion, offset(START), version).update();
        MemoryObjects objects = new MemoryObjects();
        AlightingAwareEvaluationArchiveService archive = new AlightingAwareEvaluationArchiveService(
            new JdbcAlightingAwareEvaluationArchiveStore(jdbc, transactions), objects, true, 500);
        archive.advance();
        assertThat(count("alighting_forecast_evaluation_result")).isZero();

        // 옛 schema와 원본 label을 가진 해시 고정 파일을 구성한다.
        byte[] historical = new String(objects.bytes.values().iterator().next(), StandardCharsets.UTF_8)
            .replace("alighting-evaluation-archive-v1", "les-evaluation-archive-v1")
            .getBytes(StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(historical));
        String key = "salmonbus-be/alighting-evaluation-archive/v1/" + hash + ".json";
        objects.bytes.clear();
        objects.bytes.put(key, historical);
        jdbc.sql("UPDATE alighting_evaluation_archive SET object_hash=? WHERE label_version=?")
            .params(hash, historicalVersion).update();

        assertThat(archive.restore(source, 2, AFTER, historicalVersion, 1)).isOne();
        assertThat(archive.restore(source, 2, AFTER, historicalVersion, 1)).isZero();
        assertThat(label(AFTER)).isEqualTo(before);
        assertThat(jdbc.sql("SELECT label_version FROM alighting_forecast_evaluation_result WHERE vehicle_observation_id=?")
            .param(source).query(String.class).single()).isEqualTo(historicalVersion);
        assertThat(objects.bytes).hasSize(1);
        assertThat(objects.bytes.get(key)).containsExactly(historical);
    }

    @Test
    void 원격_검증에_실패하면_정산_원본을_삭제하지_않는다() {
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

    @ParameterizedTest
    @CsvSource({"9, OBSERVATION_INPUT_LIMIT", "100, SOURCE_HISTORY_INCOMPLETE"})
    void 늦은_관측의_한도_초과와_누락은_앞선_정산에_영향을_주지_않는다(final int limit, String reason) {
        // given
        useConfirmedProfile();
        final long later = observation("later", 120, 1, 40);
        copyForecast(later);
        final long missing = observation("incomplete", 7210, 1, 40);
        jdbc.sql("UPDATE observation_batch SET ingestion_origin='LIVE',input_confirmed_at=response_received_at "
            + "WHERE route_version_id=?").param(version).update();
        jdbc.sql("UPDATE observation_batch SET stored_rows=2 WHERE id="
            + "(SELECT observation_batch_id FROM vehicle_observation WHERE id=?)").param(missing).update();

        // when
        new JdbcAlightingAwareForecastEvaluation(jdbc, quality, Clock.systemUTC(), transactions, 50_000, limit)
            .settle();

        // then
        assertThat(jdbc.sql("""
            SELECT scoring_state
            FROM alighting_forecast_evaluation_result
            WHERE vehicle_observation_id = ?
            """).param(source).query(String.class).single()).isEqualTo("SETTLED");
        assertThat(jdbc.sql("SELECT reason FROM alighting_forecast_evaluation_result WHERE vehicle_observation_id=?")
            .param(later).query(String.class).single()).isEqualTo(reason);
    }

    @ParameterizedTest
    @CsvSource({"90, UNSUPPORTED", "91, SETTLED"})
    void 자정에_확정된_누락은_수신_후_구십초까지_정산에_반영한다(final int delaySeconds, String expectedState) {
        // given
        useConfirmedProfile();
        final long midnightObservation = observation("midnight", -32_400, 1, 40);
        jdbc.sql("""
            UPDATE observation_batch
            SET ingestion_origin = 'LIVE', input_confirmed_at = response_received_at
            WHERE route_version_id = ?
            """).param(version).update();
        jdbc.sql("""
            UPDATE observation_batch
            SET response_received_at = input_confirmed_at - make_interval(secs => ?), stored_rows = 2
            WHERE id = (SELECT observation_batch_id FROM vehicle_observation WHERE id = ?)
            """).params(delaySeconds, midnightObservation).update();

        // when
        service.settle();

        // then
        assertThat(jdbc.sql("""
            SELECT scoring_state
            FROM alighting_forecast_evaluation_result
            WHERE vehicle_observation_id = ?
            """).param(source).query(String.class).single()).isEqualTo(expectedState);
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
        JdbcClient observing = mock(JdbcClient.class, delegatesTo(jdbc));
        var replay = new JdbcAlightingAwareForecastEvaluation(observing, quality, Clock.systemUTC(), heapTransactions);
        for (int i = 0; i < 5; i++) {
            replay.settle();
        }

        // then
        assertThat(count("alighting_forecast_evaluation_work")).isZero();
        assertThat(count("alighting_forecast_evaluation_result")).isEqualTo(25);
        verify(observing).sql(contains("WITH raw AS"));
        verify(observing).sql(contains("SELECT observation_batch_id,count(*) AS n"));
        assertThat(jdbc.sql("SELECT DISTINCT reason FROM alighting_forecast_evaluation_result "
            + "WHERE route_version_id=? AND vehicle_observation_id<>?")
            .params(version, source).query(String.class).list()).containsExactly("NO_CLOSED_IDENTITY_SAFE_PASS");
    }

    @Test
    void 예보_후_정원이_올라_관측이_스냅샷_정원을_넘으면_재시도_대신_사유를_남긴다() {
        useConfirmedProfile();
        jdbc.sql("UPDATE observation_batch SET ingestion_origin='LIVE',input_confirmed_at=response_received_at WHERE route_version_id=?")
            .param(version).update();
        jdbc.sql("""
            UPDATE vehicle_observation o SET remaining_seats=45 FROM observation_batch b
            WHERE o.observation_batch_id=b.id AND o.vehicle_id='current' AND o.passed_stop_order=1
                AND b.response_received_at>?
            """).param(offset(START)).update();
        service.settle();
        assertThat(jdbc.sql("SELECT reason FROM alighting_forecast_evaluation_result WHERE vehicle_observation_id=?")
            .param(source).query(String.class).single()).isEqualTo("OBSERVATION_EXCEEDS_CAPACITY_SNAPSHOT");
        assertThat(count("alighting_forecast_evaluation_work")).isZero();
    }

    @Test
    void 여러_저장_페이지가_같은_준비_이력을_공유한다() {
        useConfirmedProfile();
        tx.executeWithoutResult(status -> {
            for (int seconds = 1; seconds <= 150; seconds++) {
                long next = observation("shared-" + seconds, seconds, 1, 40);
                copyForecast(next);
            }
            jdbc.sql("UPDATE observation_batch SET ingestion_origin='LIVE',input_confirmed_at=response_received_at "
                + "WHERE route_version_id=?").param(version).update();
        });
        groupForecastSources();
        new JdbcAlightingAwareForecastEvaluation(jdbc, quality, Clock.systemUTC(), transactions,
            50_000, 150_000, 3000, 100, 8, 30).settle();
        List<String> expected = settlementResults();
        tx.executeWithoutResult(status -> {
            jdbc.sql("""
                INSERT INTO alighting_forecast_evaluation_work(vehicle_observation_id,target_stop_order,target_kind,
                    label_version,route_version_id)
                SELECT vehicle_observation_id,target_stop_order,target_kind,label_version,route_version_id
                FROM alighting_forecast_evaluation_result WHERE route_version_id=?
                """).param(version).update();
            jdbc.sql("DELETE FROM alighting_forecast_evaluation_result WHERE route_version_id=?").param(version).update();
        });
        JdbcClient observing = mock(JdbcClient.class, delegatesTo(jdbc));
        var bounded = new JdbcAlightingAwareForecastEvaluation(observing, quality, Clock.systemUTC(), transactions,
            50_000, 150_000, 3000, 500, 1, 30);

        bounded.settle();

        assertThat(count("alighting_forecast_evaluation_work")).isZero();
        assertThat(count("alighting_forecast_evaluation_result")).isEqualTo(151);
        assertThat(settlementResults()).containsExactlyElementsOf(expected);
        org.mockito.Mockito.verify(observing, org.mockito.Mockito.never()).sql(contains("WITH raw AS"));
        org.mockito.Mockito.verify(observing, org.mockito.Mockito.never()).sql(contains("SELECT observation_batch_id,count(*) AS n"));
        assertThat(jdbc.sql("SELECT scoring_state FROM alighting_forecast_evaluation_result WHERE vehicle_observation_id=?")
            .param(source).query(String.class).single()).isEqualTo("SETTLED");
    }

    @Test
    void 읽기_예산을_쓴_노선_다음부터_다음_회차를_시작한다() {
        long firstRoute = route;
        long firstVersion = version;
        long firstDeployment = deployment;
        jdbc.sql("""
            INSERT INTO alighting_forecast_evaluation_work(vehicle_observation_id,target_stop_order,target_kind,
                label_version,route_version_id)
            SELECT vehicle_observation_id,target_stop_order,seat_target,'unknown-'||n,route_version_id
            FROM seat_forecast CROSS JOIN generate_series(1,250) n WHERE vehicle_observation_id=?
            """).param(source).update();
        jdbc.sql("UPDATE model_deployment SET state='RETIRED' WHERE id=?").param(deployment).update();
        정산_자료를_준비한다();
        long secondRoute = route;
        long secondVersion = version;
        long secondDeployment = deployment;
        try {
            var bounded = new JdbcAlightingAwareForecastEvaluation(jdbc, quality, Clock.systemUTC(), transactions,
                50_000, 150_000, 3000, 100, 1, 30);
            bounded.settle();
            assertThat(jdbc.sql("SELECT count(*) FROM alighting_forecast_evaluation_work WHERE route_version_id=?")
                .param(firstVersion).query(Integer.class).single()).isEqualTo(151);
            assertThat(count("alighting_forecast_evaluation_work")).isOne();
            bounded.settle();
            assertThat(count("alighting_forecast_evaluation_work")).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM alighting_forecast_evaluation_work WHERE route_version_id=?")
                .param(firstVersion).query(Integer.class).single()).isEqualTo(151);
        } finally {
            route = firstRoute;
            version = firstVersion;
            deployment = firstDeployment;
            준비한_자료를_정리한다();
            route = secondRoute;
            version = secondVersion;
            deployment = secondDeployment;
        }
    }

    @Test
    void 준비_이력을_공유해도_저장_페이지마다_변경된_입력을_확인한다() {
        useConfirmedProfile();
        tx.executeWithoutResult(status -> {
            for (int seconds = 1; seconds <= 150; seconds++) {
                copyForecast(observation("changed-" + seconds, seconds, 1, 40));
            }
            jdbc.sql("UPDATE observation_batch SET ingestion_origin='LIVE',input_confirmed_at=response_received_at "
                + "WHERE route_version_id=?").param(version).update();
        });
        groupForecastSources();
        java.util.concurrent.atomic.AtomicInteger locks = new java.util.concurrent.atomic.AtomicInteger();
        RouteWorkAccess changing = mock(RouteWorkAccess.class, delegatesTo(quality));
        doAnswer(call -> {
            if (locks.incrementAndGet() == 2) {
                jdbc.sql("UPDATE route SET data_revision=data_revision+1 WHERE id=?").param(route).update();
            }
            return quality.lock(call.getArgument(0, Long.class));
        }).when(changing).lock(version);
        var bounded = new JdbcAlightingAwareForecastEvaluation(jdbc, changing, Clock.systemUTC(), transactions,
            50_000, 150_000, 3000, 500, 1, 30);

        bounded.settle();

        assertThat(count("alighting_forecast_evaluation_result")).isEqualTo(100);
        assertThat(count("alighting_forecast_evaluation_work")).isEqualTo(51);
    }

    @Test
    void 재시작한_정산은_새_배치만_읽고_기존_정산과_같은_결과를_낸다() {
        confirmInputs();
        JdbcClient observing = mock(JdbcClient.class, delegatesTo(jdbc));
        var store = new JdbcSettlementProjection(observing, transactions);
        var first = store.advance(version, date(), 1, 1000, confirmedSnapshot(), START.plusSeconds(40));
        long firstCount = projectionRows(first.id());
        var resumed = new JdbcSettlementProjection(observing, transactions)
            .advance(version, date(), 1, 1000, confirmedSnapshot(), START.plusSeconds(80));
        assertThat(resumed.id()).isEqualTo(first.id());
        assertThat(projectionRows(resumed.id())).isEqualTo(firstCount + 2);
        var cached = store.advance(version, date(), 1, 1000, confirmedSnapshot(), START.plusSeconds(80));
        assertThat(cached.version()).isEqualTo(resumed.version());
        org.mockito.Mockito.verify(observing, org.mockito.Mockito.times(2)).sql(contains("WITH raw AS"));
        assertProjectionMatchesHistory(store, cached, START.plusSeconds(80), 1000);
    }

    @Test
    void 늦게_들어온_관측과_원자료_수정은_이전_정산_근거를_무효화한다() {
        confirmInputs();
        var store = new JdbcSettlementProjection(jdbc, transactions);
        var first = store.advance(version, date(), 1, 1000, confirmedSnapshot(), START.plusSeconds(80));
        observation("late", 30, 1, 40);
        confirmInputs();
        assertThat(tx.<Boolean>execute(status -> store.validate(List.of(first)))).isFalse();
        var second = store.advance(version, date(), 1, 1000, confirmedSnapshot(), START.plusSeconds(80));
        assertThat(second.id()).isNotEqualTo(first.id());
        assertProjectionMatchesHistory(store, second, START.plusSeconds(80), 1000);
        jdbc.sql("UPDATE vehicle_observation SET remaining_seats=1 WHERE id=?").param(post).update();
        assertThat(tx.<Boolean>execute(status -> store.validate(List.of(second)))).isFalse();
        var third = store.advance(version, date(), 1, 1000, confirmedSnapshot(), START.plusSeconds(80));
        assertThat(third.id()).isNotEqualTo(second.id());
        assertProjectionMatchesHistory(store, third, START.plusSeconds(80), 1000);
    }

    @Test
    void 관측을_읽는_동안_입력이_바뀌면_증분_진행과_근거를_함께_버린다() {
        confirmInputs();
        JdbcClient observing = mock(JdbcClient.class, delegatesTo(jdbc));
        TransactionTemplate writer = new TransactionTemplate(transactions);
        writer.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        doAnswer(call -> {
            writer.executeWithoutResult(status -> jdbc.sql("UPDATE vehicle_observation SET remaining_seats=1 WHERE id=?")
                .param(post).update());
            return jdbc.sql(call.getArgument(0, String.class));
        }).when(observing).sql(contains("WITH raw AS"));
        var result = new JdbcSettlementProjection(observing, transactions)
            .advance(version, date(), 1, 1000, confirmedSnapshot(), START.plusSeconds(80));
        assertThat(result).isNull();
        assertThat(jdbc.sql("SELECT sum(version) FROM alighting_settlement_projection WHERE route_id=?")
            .param(route).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM alighting_settlement_observation o JOIN alighting_settlement_projection p ON p.id=o.projection_id WHERE p.route_id=?")
            .param(route).query(Long.class).single()).isZero();
    }

    @Test
    void 관측_한도_이후의_이력을_만들어도_앞선_정산_범위는_바뀌지_않는다() {
        confirmInputs();
        observation("later", 100, 1, 40);
        confirmInputs();
        var store = new JdbcSettlementProjection(jdbc, transactions);
        var projection = store.advance(version, date(), 1, 8, confirmedSnapshot(), START.plusSeconds(120));
        assertThat(projection.overflow()).isEqualTo(START.plusSeconds(100));
        assertProjectionMatchesHistory(store, projection, START.plusSeconds(80), 8);
        assertProjectionMatchesHistory(store, projection, START.plusSeconds(120), 8);
    }

    @Test
    void 배치_경계의_같은_확정_시각은_모두_읽을_때까지_닫지_않는다() {
        confirmInputs();
        for (int i = 0; i < 110; i++) {
            observation("tied-" + i, 40, 1, 40);
        }
        confirmInputs();
        var store = new JdbcSettlementProjection(jdbc, transactions);
        var first = store.advance(version, date(), 1, 1000, confirmedSnapshot(), START.plusSeconds(80));
        assertThat(first.covers(START.plusSeconds(40))).isFalse();
        var resumed = store.advance(version, date(), 1, 1000, confirmedSnapshot(), START.plusSeconds(80));
        assertThat(resumed.covers(START.plusSeconds(80))).isTrue();
        assertThat(projectionRows(resumed.id())).isEqualTo(118);
        assertProjectionMatchesHistory(store, resumed, START.plusSeconds(80), 1000);
    }

    @Test
    void 삭제된_원자료는_재구성한_근거에서_누락으로_판정한다() {
        confirmInputs();
        var store = new JdbcSettlementProjection(jdbc, transactions);
        var first = store.advance(version, date(), 1, 1000, confirmedSnapshot(), START.plusSeconds(80));
        jdbc.sql("DELETE FROM vehicle_observation WHERE id=?").param(post).update();
        assertThat(tx.<Boolean>execute(status -> store.validate(List.of(first)))).isFalse();
        var next = store.advance(version, date(), 1, 1000, confirmedSnapshot(), START.plusSeconds(80));
        assertThat(next.incomplete()).isEqualTo(START.plusSeconds(60));
        assertProjectionMatchesHistory(store, next, START.plusSeconds(80), 1000);
    }

    @Test
    void 무효화된_근거를_정리해도_진행_중인_정산의_근거는_보존한다() {
        confirmInputs();
        var store = new JdbcSettlementProjection(jdbc, transactions);
        var first = store.advance(version, date(), 1, 1000, confirmedSnapshot(), START.plusSeconds(80));
        jdbc.sql("UPDATE vehicle_observation SET remaining_seats=1 WHERE id=?").param(post).update();
        var next = store.advance(version, date(), 1, 1000, confirmedSnapshot(), START.plusSeconds(80));
        store.cleanup(date().plusDays(3));
        assertThat(projectionRows(first.id())).isZero();
        assertThat(projectionRows(next.id())).isEqualTo(8);
    }

    @Test
    void 새_통과_근거가_없으면_대기_예보를_다시_평가하지_않는다() {
        confirmInputs();
        jdbc.sql("DELETE FROM vehicle_observation WHERE id=?").param(post).update();
        jdbc.sql("UPDATE observation_batch b SET stored_rows=(SELECT count(*) FROM vehicle_observation o WHERE o.observation_batch_id=b.id) WHERE route_version_id=?")
            .param(version).update();
        var fixed = Clock.fixed(START.plusSeconds(30), ZoneOffset.UTC);
        JdbcClient observing = mock(JdbcClient.class, delegatesTo(jdbc));
        var replay = new JdbcAlightingAwareForecastEvaluation(observing, quality, fixed, transactions);
        replay.settle();
        jdbc.sql("UPDATE alighting_forecast_evaluation_work SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE route_version_id=?")
            .param(version).update();
        replay.settle();
        org.mockito.Mockito.clearInvocations(observing);
        observation("current", 35, 1, 3);
        confirmInputs();
        jdbc.sql("UPDATE alighting_forecast_evaluation_work SET next_attempt_at='-infinity' WHERE route_version_id=?")
            .param(version).update();
        jdbc.sql("UPDATE alighting_settlement_batch_work SET retry_at='-infinity',attempted_generation=enqueue_generation WHERE route_version_id=?")
            .param(version).update();
        new JdbcAlightingAwareForecastEvaluation(observing, quality, Clock.fixed(START.plusSeconds(36), ZoneOffset.UTC), transactions).settle();
        org.mockito.Mockito.verify(observing, org.mockito.Mockito.never()).sql(contains("WITH requested AS"));
        assertThat(count("alighting_forecast_evaluation_work")).isOne();
    }

    @Test
    void 기존_대기열은_커서를_이어_등록하고_동시에_추가된_작업도_빠뜨리지_않는다() {
        confirmInputs();
        jdbc.sql("UPDATE alighting_forecast_evaluation_work SET next_attempt_at='-infinity' WHERE route_version_id=?")
            .param(version).update();
        jdbc.sql("DELETE FROM alighting_settlement_batch_work WHERE route_version_id=?").param(version).update();
        jdbc.sql("UPDATE alighting_settlement_queue_scan SET observation_id=0,stop_order=0,target_kind='',label_version='',complete=false").update();
        jdbc.sql("""
            INSERT INTO alighting_forecast_evaluation_work(vehicle_observation_id,target_stop_order,target_kind,label_version,route_version_id)
            SELECT vehicle_observation_id,target_stop_order,target_kind,'retired-'||n,route_version_id
            FROM alighting_forecast_evaluation_work CROSS JOIN generate_series(1,1100) n WHERE vehicle_observation_id=?
            """).param(source).update();
        var queue = new JdbcSettlementBatchQueue(jdbc, transactions);
        assertThat(queue.backfill()).isTrue();
        assertThat(jdbc.sql("SELECT complete FROM alighting_settlement_queue_scan").query(Boolean.class).single()).isFalse();
        long earlier = jdbc.sql("SELECT min(id) FROM vehicle_observation WHERE route_version_id=?").param(version).query(Long.class).single();
        copyForecast(earlier);
        assertThat(new JdbcSettlementBatchQueue(jdbc, transactions).backfill()).isTrue();
        assertThat(jdbc.sql("SELECT complete FROM alighting_settlement_queue_scan").query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM alighting_settlement_batch_work WHERE route_version_id=?")
            .param(version).query(Long.class).single()).isEqualTo(2);
        assertThat(queue.routes(START.plusSeconds(80))).contains(version);
    }

    @Test
    void 중단된_배치의_임대가_끝나면_재개하고_이전_실행은_새_임대를_해제하지_못한다() {
        confirmInputs();
        var queue = new JdbcSettlementBatchQueue(jdbc, transactions);
        var first = queue.claim(version, START.plusSeconds(80), java.util.Set.of());
        assertThat(first).isNotNull();
        assertThat(queue.claim(version, START.plusSeconds(80), java.util.Set.of())).isNull();
        jdbc.sql("UPDATE alighting_settlement_batch_work SET lease_until=clock_timestamp()-interval '1 second' WHERE route_version_id=?")
            .param(version).update();
        var resumed = queue.claim(version, START.plusSeconds(80), java.util.Set.of());
        assertThat(resumed.lease()).isNotEqualTo(first.lease());
        queue.release(first, List.of(), true, Instant.now());
        assertThat(jdbc.sql("SELECT lease_token FROM alighting_settlement_batch_work WHERE route_version_id=?")
            .param(version).query(UUID.class).single()).isEqualTo(resumed.lease());
    }

    @Test
    void 계산_중_추가된_예측은_이전_배치의_완료_처리로_잠들지_않는다() {
        confirmInputs();
        var queue = new JdbcSettlementBatchQueue(jdbc, transactions);
        var batch = queue.claim(version, START.plusSeconds(80), java.util.Set.of());
        jdbc.sql("""
            INSERT INTO seat_forecast
            SELECT (jsonb_populate_record(NULL::seat_forecast,to_jsonb(f)||'{"target_stop_order":3}'::jsonb)).*
            FROM seat_forecast f WHERE vehicle_observation_id=?
            """).param(source).update();
        queue.release(batch, List.of(), true, Instant.now());
        assertThat(jdbc.sql("SELECT enqueue_generation>attempted_generation FROM alighting_settlement_batch_work WHERE route_version_id=?")
            .param(version).query(Boolean.class).single()).isTrue();
        var resumed = queue.claim(version, START.plusSeconds(80), java.util.Set.of());
        assertThat(resumed).isNotNull();
        queue.release(resumed, List.of(), true, Instant.now());
        jdbc.sql("UPDATE alighting_forecast_evaluation_work SET next_attempt_at=next_attempt_at WHERE vehicle_observation_id=?")
            .param(source).update();
        assertThat(jdbc.sql("SELECT enqueue_generation>attempted_generation FROM alighting_settlement_batch_work WHERE route_version_id=?")
            .param(version).query(Boolean.class).single()).isTrue();
    }

    @Test
    void 자정_직후의_관측은_전날_예측의_정산도_깨운다() {
        confirmInputs();
        long midnight = observation("midnight", 15 * 3600 + 60, 1, 40);
        confirmInputs();
        assertThat(jdbc.sql("SELECT source_date FROM alighting_settlement_generation WHERE route_id=? ORDER BY source_date")
            .param(route).query(java.time.LocalDate.class).list()).contains(date(), date().plusDays(1));
        var store = new JdbcSettlementProjection(jdbc, transactions);
        var priorDay = store.advance(version, date(), 1, 1000, confirmedSnapshot(), START.plusSeconds(16 * 3600));
        jdbc.sql("UPDATE vehicle_observation SET remaining_seats=1 WHERE id=?").param(midnight).update();
        assertThat(tx.<Boolean>execute(status -> store.validate(List.of(priorDay)))).isFalse();
    }

    @Test
    void 입력_계약만으로_지원불가인_예측은_이력을_준비하지_않는다() {
        confirmInputs();
        jdbc.sql("UPDATE seat_forecast SET feature_snapshot=feature_snapshot || '{\"inputProfile\":\"retired\"}'::jsonb WHERE vehicle_observation_id=?")
            .param(source).update();
        JdbcClient observing = mock(JdbcClient.class, delegatesTo(jdbc));
        new JdbcAlightingAwareForecastEvaluation(observing, quality, Clock.systemUTC(), transactions).settle();
        org.mockito.Mockito.verify(observing, org.mockito.Mockito.never()).sql(contains("WITH raw AS"));
        assertThat(jdbc.sql("SELECT count(*) FROM alighting_settlement_projection WHERE route_id=?")
            .param(route).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT reason FROM alighting_forecast_evaluation_result WHERE vehicle_observation_id=?")
            .param(source).query(String.class).single()).isEqualTo("INPUT_PROFILE_MISMATCH");
    }

    private java.time.LocalDate date() {
        return START.atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate();
    }

    private tools.jackson.databind.JsonNode confirmedSnapshot() {
        return tools.jackson.databind.json.JsonMapper.builder().build().readTree(jdbc.sql("SELECT feature_snapshot::text FROM seat_forecast WHERE vehicle_observation_id=?")
            .param(source).query(String.class).single());
    }

    private void confirmInputs() {
        useConfirmedProfile();
        jdbc.sql("UPDATE observation_batch SET ingestion_origin='LIVE',input_confirmed_at=response_received_at WHERE route_version_id=?")
            .param(version).update();
    }

    private long projectionRows(long id) {
        return jdbc.sql("SELECT count(*) FROM alighting_settlement_observation WHERE projection_id=?").param(id).query(Long.class).single();
    }

    private void assertProjectionMatchesHistory(JdbcSettlementProjection store, JdbcSettlementProjection.Ref ref, Instant through, int limit) {
        var request = new JdbcSettlementProjection.Request(source, 2, through);
        var actual = store.evaluate(ref, through, confirmedSnapshot(), store.matches(ref, List.of(request)).get(request));
        var history = new ConfirmedAlightingAwareEvaluationOracle.History(jdbc, version, date(), through, limit);
        var expected = new ConfirmedAlightingAwareEvaluationOracle(history, date(), through).evaluate(source, 2, confirmedSnapshot());
        assertThat(actual).isEqualTo(expected);
    }

    private void groupForecastSources() {
        tx.executeWithoutResult(status -> {
            jdbc.sql("""
                UPDATE vehicle_observation o SET observation_batch_id=(SELECT observation_batch_id FROM vehicle_observation WHERE id=:source),
                    source_row_number=CAST(o.id AS integer)
                WHERE o.route_version_id=:route AND EXISTS(SELECT 1 FROM seat_forecast f WHERE f.vehicle_observation_id=o.id)
                """).param("source", source).param("route", version).update();
            jdbc.sql("""
                DELETE FROM observation_batch b WHERE b.route_version_id=?
                AND NOT EXISTS(SELECT 1 FROM vehicle_observation o WHERE o.observation_batch_id=b.id)
                """).param(version).update();
            jdbc.sql("""
                UPDATE observation_batch b SET stored_rows=(SELECT count(*) FROM vehicle_observation o WHERE o.observation_batch_id=b.id)
                WHERE b.route_version_id=?
                """).param(version).update();
            jdbc.sql("""
                SELECT enqueue_alighting_settlement_batch(vehicle_observation_id,label_version,next_attempt_at)
                FROM alighting_forecast_evaluation_work WHERE route_version_id=?
                """).param(version).query((rs, n) -> 1).list();
        });
    }

    private List<String> settlementResults() {
        return jdbc.sql("""
            SELECT (to_jsonb(result)-'scored_at')::text FROM alighting_forecast_evaluation_result result
            WHERE route_version_id=? ORDER BY vehicle_observation_id,target_stop_order,target_kind,label_version
            """).param(version).query(String.class).list();
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
