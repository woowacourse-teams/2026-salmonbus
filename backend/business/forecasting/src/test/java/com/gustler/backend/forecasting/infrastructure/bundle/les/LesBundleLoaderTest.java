package com.gustler.backend.forecasting.infrastructure.bundle.les;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.forecasting.domain.model.les.LesSeatPredictor;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 작은 합성 모델로 파일 손상과 계약 혼용을 검사한다. 실제 모델 대조는 verifyLesParity에서 한다. */
class LesBundleLoaderTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> ROUTES = List.of("3330", "1650", "9007", "9300", "6011", "3000", "5600", "3500");
    @TempDir
    Path directory;

    @Test
    void 좌석과_확률_모델을_독립적으로_적재한다() throws Exception {
        fixture();
        LesInferenceBundle loaded = LesInferenceBundle.load(directory);
        LesSeatPredictor.Prediction prediction = loaded.predictor().predict(new double[88], new double[80]);
        assertThat(prediction.afterAlightingSeats()).isCloseTo((1.3 + 10) / 3,
            org.assertj.core.data.Offset.offset(1e-14));
        assertThat(prediction.departureFullProbability()).isEqualTo(0.5);
        assertThat(loaded.statistics("3330").get("min_per_stop").doubleValue()).isEqualTo(1);
    }

    @Test
    void 변경된_모델_파일을_거부한다() throws Exception {
        fixture();
        Files.writeString(directory.resolve("base.json"), "{}");
        assertThatThrownBy(() -> LesInferenceBundle.load(directory)).hasMessageContaining("해시");
    }

    @Test
    void 해시가_갱신돼도_입력_순서_변경을_거부한다() throws Exception {
        fixture();
        JsonNode contract = JSON.readTree(Files.readAllBytes(directory.resolve("feature-contract.json")));
        String changed = JSON.writeValueAsString(contract).replace("\"intercept\",\"morning\"",
            "\"morning\",\"intercept\"");
        assertThat(changed).isNotEqualTo(JSON.writeValueAsString(contract));
        Files.writeString(directory.resolve("feature-contract.json"), changed);
        manifest();
        assertThatThrownBy(() -> LesInferenceBundle.load(directory)).hasMessageContaining("순서");
    }

    @Test
    void 누락된_통계와_다른_목표를_거부한다() throws Exception {
        fixture();
        Files.delete(directory.resolve("statistics-3330.json"));
        manifest();
        assertThatThrownBy(() -> LesInferenceBundle.load(directory)).hasMessageContaining("필수 파일");
        fixture();
        Files.writeString(directory.resolve("constants.json"),
            "{\"target\":\"arrive\",\"classes\":[0,1,2],\"middle\":1.3}");
        manifest();
        assertThatThrownBy(() -> LesInferenceBundle.load(directory)).hasMessageContaining("목표");
    }

    @Test
    void 운영_로더는_미검증_번들을_거부한다() throws Exception {
        fixture();
        assertThatThrownBy(() -> LesInferenceBundle.loadForServing(directory)).hasMessageContaining("서빙 검증");
    }

    @Test
    void 활성화_표시만_바꿔_검증을_우회할_수_없다() throws Exception {
        fixture();
        String manifest = Files.readString(directory.resolve("manifest.json"));
        Files.writeString(directory.resolve("manifest.json"),
            manifest.replace("\"serving_ready\":false", "\"serving_ready\":true"));
        assertThatThrownBy(() -> LesInferenceBundle.loadForServing(directory)).hasMessageContaining("서빙 계약");
    }

    @Test
    void 내용이_같아도_심볼릭_링크를_거부한다() throws Exception {
        fixture();
        Path original = directory.resolve("base-original.json");
        Files.move(directory.resolve("base.json"), original);
        Files.createSymbolicLink(directory.resolve("base.json"), original);
        assertThatThrownBy(() -> LesInferenceBundle.load(directory)).hasMessageContaining("일반 파일");
    }

    @Test
    void 운영_적재는_원본에_묶인_증거와_기준_계산을_검사한다() throws Exception {
        fixture();
        servingFixture((1.3 + 10) / 3);
        assertThat(LesInferenceBundle.loadForServing(directory).digest()).hasSize(64);
    }

    @Test
    void 성공_증거가_있어도_기준_계산_불일치를_거부한다() throws Exception {
        fixture();
        servingFixture(40);
        assertThatThrownBy(() -> LesInferenceBundle.loadForServing(directory)).hasMessageContaining("대조 예측");
    }

    @Test
    void 운영_시작_기준보다_미래_통계를_거부한다() throws Exception {
        fixture();
        servingFixture((1.3 + 10) / 3);
        JsonNode manifest = JSON.readTree(Files.readAllBytes(directory.resolve("manifest.json")));
        ((ObjectNode) manifest.get("runtime")).put("data_through", "2026-10-20T00:00:00Z");
        Files.writeString(directory.resolve("manifest.json"), JSON.writeValueAsString(manifest));
        assertThatThrownBy(() -> LesInferenceBundle.loadForServing(directory)).hasMessageContaining("미래 통계");
    }

    private void servingFixture(final double seats) throws Exception {
        Map<String, Object> golden = Map.of("seat_inputs", List.of(new double[88]), "probability_inputs",
            List.of(new double[80]),
            "expected_base", List.of(10), "expected_gate_probabilities", List.of(List.of(1.0 / 3, 1.0 / 3, 1.0 / 3)),
            "expected_seats", List.of(seats), "expected_departure_full", List.of(0.5));
        Files.writeString(directory.resolve("golden.json"), JSON.writeValueAsString(golden));
        Files.writeString(directory.resolve("boundaries.json"), JSON.writeValueAsString(golden));
        manifest();
        byte[] source = Files.readAllBytes(directory.resolve("manifest.json"));
        String sourceHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));
        Files.write(directory.resolve("verification-source-manifest.json"), source);
        for (String check : List.of("feature_parity", "historical_replay", "settlement", "runtime_validation")) {
            String evidence = "{\"syntheticFixture\":true}";
            Files.writeString(directory.resolve("evidence-" + check + ".json"), evidence);
            String hash = HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(evidence.getBytes(StandardCharsets.UTF_8)));
            Files.writeString(directory.resolve("verification-" + check + ".json"), JSON.writeValueAsString(Map.of(
                "schema", "les-serving-verification-v1", "check", check, "status", "PASS", "verified_cases", 1,
                "source_manifest_sha256", sourceHash, "feature_contract_version", "les-online-v1",
                "evidence_file", "evidence-" + check + ".json", "evidence_sha256", hash)));
        }
        manifest();
        ObjectNode root = (ObjectNode) JSON.readTree(Files.readAllBytes(directory.resolve("manifest.json")));
        root.put("serving_ready", true);
        root.set("runtime",
            JSON.valueToTree(Map.of("release_id", "test-les", "feature_contract_version", "les-online-v1",
                "data_through", "2026-10-21T00:00:00Z", "source_manifest_sha256", sourceHash, "routes", ROUTES,
                "scope", "weekday-rush-6-10-17-21-horizon-1-12")));
        Files.writeString(directory.resolve("manifest.json"), JSON.writeValueAsString(root));
    }

    private void fixture() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/les/feature-contract.json")) {
            Files.write(directory.resolve("feature-contract.json"), input.readAllBytes());
        }
        Files.writeString(directory.resolve("constants.json"),
            "{\"target\":\"after\",\"classes\":[0,1,2],\"middle\":1.3}");
        model("base", "regression", 88, List.of(), List.of(10));
        model("gate", "multiclass", 88, List.of(0, 1, 2), List.of(0, 0, 0));
        model("pool", "binary", 80, List.of(0, 1), List.of(0));
        Files.writeString(directory.resolve("statistics-manifest.json"),
            JSON.writeValueAsString(Map.of("schema", "les-statistics-v1", "routes", ROUTES)));
        Files.writeString(directory.resolve("route-reference.json"),
            JSON.writeValueAsString(Map.of("routes", ROUTES.stream().map(r -> Map.of("modelRoute", r)).toList())));
        for (String route : ROUTES) {
            Files.writeString(directory.resolve("statistics-" + route + ".json"), JSON.writeValueAsString(Map.of(
                "route", route, "min_per_stop", 1, "tables", Map.of(), "alighting", Map.of(), "route_metadata",
                List.of(),
                "events_known_through", "2026-10-21T00:00:00Z", "observations_through", "2026-10-21T00:00:00Z")));
        }
        manifest();
    }

    private void model(
        String name,
        String kind,
        final int size,
        List<Integer> classes,
        List<Integer> baseline)
        throws Exception {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schema", "les-numeric-hgb-v1");
        value.put("kind", kind);
        value.put("classes", classes);
        value.put("feature_count", size);
        value.put("baseline", baseline);
        value.put("leaf_values_include_learning_rate", true);
        value.put("node_fields", List.of("feature", "threshold", "missing_left", "left", "right", "value", "leaf"));
        value.put("trees", List.of(baseline.stream().map(v -> List.of(List.of(0, 0, false, 0, 0, 0, true))).toList()));
        Files.writeString(directory.resolve(name + ".json"), JSON.writeValueAsString(value));
    }

    private void manifest() throws Exception {
        Map<String, String> hashes = new LinkedHashMap<>();
        try (Stream<Path> stream = Files.list(directory)) {
            for (Path path : stream.filter(p -> !p.getFileName().toString().equals("manifest.json")).toList()) {
                hashes.put(path.getFileName().toString(),
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
            }
        }
        Files.writeString(directory.resolve("manifest.json"), JSON.writeValueAsString(Map.of(
            "schema", "les-inference-bundle-v1", "model", "les-gate-base-and-pool", "serving_ready", false,
            "output_targets", List.of("after-alighting-estimated", "departure-zero"), "files", hashes)),
            StandardCharsets.UTF_8);
    }
}
