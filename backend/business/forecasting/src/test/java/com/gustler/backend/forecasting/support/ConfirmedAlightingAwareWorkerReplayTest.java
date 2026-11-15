package com.gustler.backend.forecasting.support;

import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.forecasting.application.pipeline.PassLabelStore;
import com.gustler.backend.forecasting.application.pipeline.StopPassStore;
import com.gustler.backend.forecasting.domain.deployment.ModelDeploymentRepository;
import com.gustler.backend.forecasting.infrastructure.pipeline.JdbcForecastRecordStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 합성 재생 자료({@link SyntheticWorkerReplay})로 재생을 끝까지 돌려 보고서를 본다. 실제 운영 자료로 모델을 확인한 것이 아니다.
 *
 * <p>예보와 채점은 실제 DB에 쓴다. 테스트 트랜잭션 하나에 모든 쓰기가 합류하고 끝나면 되돌린다.
 */
@ForecastingIntegrationTest
@Transactional
class ConfirmedAlightingAwareWorkerReplayTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    JdbcClient jdbc;
    @Autowired
    PlatformTransactionManager transactions;
    @Autowired
    ModelDeploymentRepository deployments;
    @Autowired
    JdbcForecastRecordStore records;
    @Autowired
    StopPassStore passes;
    @Autowired
    PassLabelStore labels;
    @TempDir
    Path directory;

    @Test
    void 출퇴근_재생_자료를_끝까지_돌리면_영수증이_읽는_항목을_갖춘_통과_보고서를_쓴다() throws IOException {
        // given
        SyntheticWorkerReplay synthetic = SyntheticWorkerReplay.rush();
        Path fixtures = synthetic.writeFixtures(directory.resolve("fixtures"));
        Path bundle = synthetic.writeBundle(directory.resolve("bundle"));
        Path output = directory.resolve("output");
        String digest = SyntheticWorkerReplay.bundleDigest(bundle);

        // when
        Map<String, Object> returned = ConfirmedAlightingAwareWorkerReplay.run(fixtures, bundle, output, null,
            stores());

        // then
        JsonNode report = JSON.readTree(Files.readAllBytes(output.resolve("report.json")));
        JsonNode route = report.path("routes").get(0);
        JsonNode routeFile = JSON.readTree(Files.readAllBytes(output.resolve("3330.json")));
        assertSoftly(softly -> {
            softly.assertThat(returned.get("passed")).isEqualTo(true);
            softly.assertThat(report.path("passed").asBoolean()).isTrue();
            softly.assertThat(report.path("routes")).hasSize(1);
            softly.assertThat(report.path("bundle_manifest_sha256").asString()).isEqualTo(digest);
            softly.assertThat(report.path("serving_ready").asBoolean()).isFalse();
            softly.assertThat(report.path("settlement_scope").asString()).isEqualTo("after_alighting_only");
            softly.assertThat(report.path("runtime_capacity_policy").asString())
                .isEqualTo("vehicle-capacity-snapshot-v1");
            softly.assertThat(report.path("runtime_label_version").asString())
                .isEqualTo("alighting-confirmed-capacity-snapshot-v3");
            softly.assertThat(report.path("runtime_calculation_version").asString())
                .isEqualTo("alighting-online-capacity-snapshot-v2");
            softly.assertThat(route.path("route").asString()).isEqualTo(SyntheticWorkerReplay.ROUTE);
            softly.assertThat(route.path("passed").asBoolean()).isTrue();
            softly.assertThat(route.path("cases").asInt()).isEqualTo(2);
            softly.assertThat(route.path("settled_targets").asInt()).isEqualTo(2);
            softly.assertThat(route.path("expected_unsupported").asInt()).isZero();
            softly.assertThat(route.path("failures")).isEmpty();
            softly.assertThat(route.path("max_feature_difference").asDouble()).isZero();
            softly.assertThat(route.path("max_prediction_difference").asDouble()).isBetween(0.0, 1e-9);
            softly.assertThat(route.path("max_label_difference").asDouble()).isBetween(0.0, 1e-8);
            softly.assertThat(route.path("extended_hours").toString())
                .isEqualTo("[{\"route\":\"3330\",\"cell\":\"work-morning\",\"published\":2,\"settled\":2}]");
            softly.assertThat(route.path("restored_targets").asInt()).isZero();
            softly.assertThat(routeFile).isEqualTo(route);
        });
    }

    @Test
    void 재생은_그날의_배치마다_예보_실행을_남기고_배치의_입력_확정_시각은_쓰지_않으며_모든_예측을_닫는다() throws IOException {
        // given
        SyntheticWorkerReplay synthetic = SyntheticWorkerReplay.rush();
        Path fixtures = synthetic.writeFixtures(directory.resolve("fixtures"));
        Path bundle = synthetic.writeBundle(directory.resolve("bundle"));

        // when
        ConfirmedAlightingAwareWorkerReplay.run(fixtures, bundle, directory.resolve("output"), null, stores());

        // then
        final long routeId = jdbc.sql("SELECT id FROM route WHERE display_name=? AND source_route_id='204000057'")
            .param(SyntheticWorkerReplay.ROUTE).query(Long.class).single();
        assertSoftly(softly -> {
            softly.assertThat(count("SELECT count(*) FROM forecast_run WHERE route_id=?", routeId)).isEqualTo(22);
            softly.assertThat(count("""
                SELECT count(*) FROM forecast_run run JOIN observation_batch batch
                  ON batch.id=run.observation_batch_id
                WHERE run.route_id=? AND run.confirmed_at=batch.response_received_at+interval '2.345678 seconds'
                """, routeId)).isEqualTo(22);
            softly.assertThat(count("""
                SELECT count(*) FROM observation_batch batch JOIN route_version version
                  ON version.id=batch.route_version_id
                WHERE version.route_id=? AND batch.input_confirmed_at IS NOT NULL
                """, routeId)).isZero();
            softly.assertThat(count("SELECT count(*) FROM forecast_outcome WHERE route_id=? AND state='SETTLED'",
                routeId)).isPositive();
            softly.assertThat(count("SELECT count(*) FROM forecast_outcome WHERE route_id=? AND state='PENDING'",
                routeId)).isZero();
        });
    }

    @Test
    void 시간_확장_재생_자료는_시간_칸별_발행과_정산_수와_다시_채점한_정답을_보고한다() throws IOException {
        // given
        SyntheticWorkerReplay synthetic = SyntheticWorkerReplay.extended();
        Path fixtures = synthetic.writeFixtures(directory.resolve("fixtures"));
        Path bundle = synthetic.writeBundle(directory.resolve("bundle"));
        Path output = directory.resolve("output");

        // when
        ConfirmedAlightingAwareWorkerReplay.run(fixtures, bundle, output, null, stores());

        // then
        JsonNode report = JSON.readTree(Files.readAllBytes(output.resolve("report.json")));
        JsonNode route = report.path("routes").get(0);
        assertSoftly(softly -> {
            softly.assertThat(report.path("passed").asBoolean()).isTrue();
            softly.assertThat(route.path("passed").asBoolean()).isTrue();
            softly.assertThat(route.path("cases").asInt()).isEqualTo(2);
            softly.assertThat(route.path("settled_targets").asInt()).isEqualTo(1);
            softly.assertThat(route.path("expected_unsupported").asInt()).isEqualTo(1);
            softly.assertThat(route.path("restored_targets").asInt()).isEqualTo(1);
            softly.assertThat(route.path("failures")).isEmpty();
            softly.assertThat(route.path("max_feature_difference").asDouble()).isZero();
            softly.assertThat(route.path("max_prediction_difference").asDouble()).isBetween(0.0, 1e-9);
            softly.assertThat(route.path("max_label_difference").asDouble()).isBetween(0.0, 1e-8);
            softly.assertThat(route.path("extended_hours").toString())
                .isEqualTo("[{\"route\":\"3330\",\"cell\":\"work-day\",\"published\":2,\"settled\":1}]");
            softly.assertThat(route.path("settled_boundaries")).isEmpty();
        });
    }

    @Test
    void 입력이_어긋난_사례가_있으면_입력_차이를_적은_보고서를_쓰고_실패로_끝낸다() throws IOException {
        // given
        SyntheticWorkerReplay synthetic = SyntheticWorkerReplay.rush();
        ArrayNode seats = (ArrayNode) synthetic.caseAt(0).get("seat88");
        seats.set(52, seats.get(52).asDouble() + 1);

        // when
        Rejected rejected = replayExpectingRejection(synthetic);

        // then
        assertSoftly(softly -> {
            softly.assertThat(rejected.thrown()).isInstanceOf(IllegalStateException.class);
            softly.assertThat(rejected.report().path("passed").asBoolean()).isFalse();
            softly.assertThat(rejected.route().path("passed").asBoolean()).isFalse();
            softly.assertThat(rejected.route().path("max_feature_difference").asDouble()).isEqualTo(1.0);
        });
    }

    @Test
    void 예측이_어긋난_사례가_있으면_예측_차이를_적은_보고서를_쓰고_실패로_끝낸다() throws IOException {
        // given
        SyntheticWorkerReplay synthetic = SyntheticWorkerReplay.extended();
        ObjectNode row = synthetic.caseAt(0);
        row.put("expected_seats", row.path("expected_seats").asDouble() + 0.25);

        // when
        Rejected rejected = replayExpectingRejection(synthetic);

        // then
        assertSoftly(softly -> {
            softly.assertThat(rejected.thrown()).isInstanceOf(IllegalStateException.class);
            softly.assertThat(rejected.report().path("passed").asBoolean()).isFalse();
            softly.assertThat(rejected.route().path("passed").asBoolean()).isFalse();
            softly.assertThat(rejected.route().path("max_prediction_difference").asDouble()).isEqualTo(0.25);
            softly.assertThat(rejected.route().path("max_feature_difference").asDouble()).isZero();
        });
    }

    @Test
    void 정답_상태가_기대와_다른_사례는_실패_목록에_남기고_실패로_끝낸다() throws IOException {
        // given
        SyntheticWorkerReplay synthetic = SyntheticWorkerReplay.extended();
        synthetic.caseAt(1).remove("expected_reason");

        // when
        Rejected rejected = replayExpectingRejection(synthetic);

        // then
        assertSoftly(softly -> {
            softly.assertThat(rejected.thrown()).isInstanceOf(IllegalStateException.class);
            softly.assertThat(rejected.route().path("passed").asBoolean()).isFalse();
            softly.assertThat(rejected.route().path("expected_unsupported").asInt()).isZero();
            softly.assertThat(rejected.route().path("settled_targets").asInt()).isEqualTo(1);
            softly.assertThat(rejected.route().path("failures")).hasSize(1);
            softly.assertThat(rejected.route().path("failures").path(0).path("state").asString())
                .contains("state=UNSUPPORTED", "reason=NO_PREVIOUS_PASS");
        });
    }

    @Test
    void 정답이_어긋난_사례가_있으면_정답_차이를_적은_보고서를_쓰고_실패로_끝낸다() throws IOException {
        // given
        SyntheticWorkerReplay synthetic = SyntheticWorkerReplay.rush();
        ObjectNode row = synthetic.caseAt(1);
        row.put("expected_after", row.path("expected_after").asDouble() + 0.5);

        // when
        Rejected rejected = replayExpectingRejection(synthetic);

        // then
        assertSoftly(softly -> {
            softly.assertThat(rejected.thrown()).isInstanceOf(IllegalStateException.class);
            softly.assertThat(rejected.report().path("passed").asBoolean()).isFalse();
            softly.assertThat(rejected.route().path("passed").asBoolean()).isFalse();
            softly.assertThat(rejected.route().path("max_label_difference").asDouble()).isBetween(0.49, 0.51);
            softly.assertThat(rejected.route().path("settled_targets").asInt()).isEqualTo(2);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"expected_pre", "expected_post", "expected_closure", "expected_previous_post",
        "expected_capacity"})
    void 정답의_근거가_어긋난_사례는_실패_목록에_남기고_실패로_끝낸다(String field) throws IOException {
        // given
        SyntheticWorkerReplay synthetic = SyntheticWorkerReplay.rush();
        ObjectNode row = synthetic.caseAt(0);
        row.put(field, row.path(field).asLong() + 1);

        // when
        Rejected rejected = replayExpectingRejection(synthetic);

        // then
        assertSoftly(softly -> {
            softly.assertThat(rejected.thrown()).isInstanceOf(IllegalStateException.class);
            softly.assertThat(rejected.route().path("passed").asBoolean()).isFalse();
            softly.assertThat(rejected.route().path("failures")).hasSize(1);
            softly.assertThat(rejected.route().path("failures").path(0).has("evidence")).isTrue();
            softly.assertThat(rejected.route().path("failures").path(0).path("id").asLong())
                .isEqualTo(row.path("source_id").asLong());
        });
    }

    @Test
    void 정답이_알려진_시각이_어긋난_사례는_실패_목록에_남기고_실패로_끝낸다() throws IOException {
        // given
        SyntheticWorkerReplay synthetic = SyntheticWorkerReplay.rush();
        synthetic.caseAt(0).put("expected_known", "2026-11-03T22:07:00Z");

        // when
        Rejected rejected = replayExpectingRejection(synthetic);

        // then
        assertSoftly(softly -> {
            softly.assertThat(rejected.thrown()).isInstanceOf(IllegalStateException.class);
            softly.assertThat(rejected.route().path("passed").asBoolean()).isFalse();
            softly.assertThat(rejected.route().path("failures")).hasSize(1);
            softly.assertThat(rejected.route().path("failures").path(0).has("evidence")).isTrue();
        });
    }

    @Test
    void 수신_순서와_착수_순서가_뒤집혀_자료의_착수_시각에_실행되지_않은_배치가_있으면_실패_목록에_남기고_실패로_끝낸다()
        throws IOException {
        // given
        SyntheticWorkerReplay synthetic = SyntheticWorkerReplay.rush();
        synthetic.confirmLate(3, Duration.ofSeconds(35));

        // when
        Rejected rejected = replayExpectingRejection(synthetic);

        // then
        assertSoftly(softly -> {
            softly.assertThat(rejected.thrown()).isInstanceOf(IllegalStateException.class);
            softly.assertThat(rejected.route().path("passed").asBoolean()).isFalse();
            softly.assertThat(rejected.route().path("failures").path(0).path("unapplied_batches").asInt())
                .isEqualTo(1);
            softly.assertThat(rejected.route().path("failures").path(0).path("first_batch").asLong())
                .isEqualTo(880_100_013L);
        });
    }

    @Test
    void 번들의_노선_가운데_재생하지_않은_노선이_있으면_재생한_노선이_통과해도_통과하지_못한다() throws IOException {
        // given
        SyntheticWorkerReplay synthetic = SyntheticWorkerReplay.rush();
        Path fixtures = synthetic.writeFixtures(directory.resolve("fixtures"));
        Path bundle = synthetic.writeBundle(directory.resolve("bundle"), List.of(SyntheticWorkerReplay.ROUTE, "1650"));
        Path output = directory.resolve("output");

        // when
        Throwable thrown = catchThrowable(() -> ConfirmedAlightingAwareWorkerReplay.run(fixtures, bundle, output,
            null, stores()));

        // then
        JsonNode report = JSON.readTree(Files.readAllBytes(output.resolve("report.json")));
        assertSoftly(softly -> {
            softly.assertThat(thrown).isInstanceOf(IllegalStateException.class);
            softly.assertThat(report.path("passed").asBoolean()).isFalse();
            softly.assertThat(report.path("routes")).hasSize(1);
            softly.assertThat(report.path("routes").path(0).path("passed").asBoolean()).isTrue();
        });
    }

    @Test
    void 노선_하나만_골라_재생하면_그_노선의_결과로_통과를_정한다() throws IOException {
        // given
        SyntheticWorkerReplay synthetic = SyntheticWorkerReplay.rush();
        Path fixtures = synthetic.writeFixtures(directory.resolve("fixtures"));
        Path bundle = synthetic.writeBundle(directory.resolve("bundle"), List.of(SyntheticWorkerReplay.ROUTE, "1650"));

        // when
        Map<String, Object> report = ConfirmedAlightingAwareWorkerReplay.run(fixtures, bundle,
            directory.resolve("output"), SyntheticWorkerReplay.ROUTE, stores());

        // then
        assertSoftly(softly -> {
            softly.assertThat(report.get("passed")).isEqualTo(true);
            softly.assertThat((List<?>) report.get("routes")).hasSize(1);
        });
    }

    @Test
    void 노선_파일이_바뀐_재생_자료는_재생하지_않는다() throws IOException {
        // given
        SyntheticWorkerReplay synthetic = SyntheticWorkerReplay.rush();
        Path fixtures = synthetic.writeFixtures(directory.resolve("fixtures"));
        Path bundle = synthetic.writeBundle(directory.resolve("bundle"));
        Path routeFile = fixtures.resolve("3330.json");
        Files.writeString(routeFile, Files.readString(routeFile) + " ");
        Path output = directory.resolve("output");

        // when
        Throwable thrown = catchThrowable(() -> ConfirmedAlightingAwareWorkerReplay.run(fixtures, bundle, output,
            null, stores()));

        // then
        assertSoftly(softly -> {
            softly.assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("3330.json");
            softly.assertThat(output.resolve("report.json")).doesNotExist();
            softly.assertThat(count("SELECT count(*) FROM route WHERE source_route_id=?", "204000057")).isZero();
        });
    }

    @Test
    void 정원_정책이_지금과_다른_재생_자료는_재생하지_않는다() throws IOException {
        // given
        SyntheticWorkerReplay synthetic = SyntheticWorkerReplay.rush();
        Path fixtures = synthetic.writeFixtures(directory.resolve("fixtures"));
        Path bundle = synthetic.writeBundle(directory.resolve("bundle"));
        Path manifest = fixtures.resolve("manifest.json");
        Files.writeString(manifest, Files.readString(manifest)
            .replace("vehicle-capacity-snapshot-v1", "observed-vehicle-prefix-max-through-closure-v2"));
        Path output = directory.resolve("output");

        // when
        Throwable thrown = catchThrowable(() -> ConfirmedAlightingAwareWorkerReplay.run(fixtures, bundle, output,
            null, stores()));

        // then
        assertSoftly(softly -> {
            softly.assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
            softly.assertThat(output).doesNotExist();
        });
    }

    @Test
    void 출력_폴더가_이미_있으면_재생하지_않고_그_폴더를_건드리지_않는다() throws IOException {
        // given
        SyntheticWorkerReplay synthetic = SyntheticWorkerReplay.rush();
        Path fixtures = synthetic.writeFixtures(directory.resolve("fixtures"));
        Path bundle = synthetic.writeBundle(directory.resolve("bundle"));
        Path output = Files.createDirectory(directory.resolve("output"));

        // when
        Throwable thrown = catchThrowable(() -> ConfirmedAlightingAwareWorkerReplay.run(fixtures, bundle, output,
            null, stores()));

        // then
        assertSoftly(softly -> {
            softly.assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
            softly.assertThat(output).isEmptyDirectory();
        });
    }

    /** 자료를 쓰고 재생해, 던진 예외와 그래도 남긴 보고서를 돌려준다. */
    private Rejected replayExpectingRejection(SyntheticWorkerReplay synthetic) throws IOException {
        Path fixtures = synthetic.writeFixtures(directory.resolve("fixtures"));
        Path bundle = synthetic.writeBundle(directory.resolve("bundle"));
        Path output = directory.resolve("output");
        Throwable thrown = catchThrowable(() -> ConfirmedAlightingAwareWorkerReplay.run(fixtures, bundle, output,
            null, stores()));
        return new Rejected(thrown, JSON.readTree(Files.readAllBytes(output.resolve("report.json"))));
    }

    private ConfirmedAlightingAwareWorkerReplay.Stores stores() {
        return new ConfirmedAlightingAwareWorkerReplay.Stores(jdbc, transactions, deployments, records, passes,
            labels);
    }

    private long count(String sql, Object parameter) {
        return jdbc.sql(sql).param(parameter).query(Long.class).single();
    }

    private record Rejected(Throwable thrown, JsonNode report) {
        private JsonNode route() {
            return report.path("routes").path(0);
        }
    }
}
