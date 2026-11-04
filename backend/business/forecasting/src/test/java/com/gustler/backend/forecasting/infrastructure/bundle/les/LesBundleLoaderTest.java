package com.gustler.backend.forecasting.infrastructure.bundle.les;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** 작은 합성 모델로 파일 손상과 계약 혼용을 검사한다. 실제 모델 대조는 verifyLesParity에서 한다. */
class LesBundleLoaderTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> ROUTES = List.of("3330", "1650", "9007", "9300", "6011", "3000", "5600", "3500");
    @TempDir
    Path directory;

    @Test
    void loadsIndependentSeatAndProbabilityModels() throws Exception {
        fixture();
        var loaded = LesInferenceBundle.load(directory);
        var prediction = loaded.predictor().predict(new double[88], new double[80]);
        assertThat(prediction.afterAlightingSeats()).isCloseTo((1.3 + 10) / 3, org.assertj.core.data.Offset.offset(1e-14));
        assertThat(prediction.departureFullProbability()).isEqualTo(0.5);
        assertThat(loaded.statistics("3330").get("min_per_stop").doubleValue()).isEqualTo(1);
    }

    @Test
    void rejectsChangedModelBytes() throws Exception {
        fixture();
        Files.writeString(directory.resolve("base.json"), "{}");
        assertThatThrownBy(() -> LesInferenceBundle.load(directory)).hasMessageContaining("해시");
    }

    @Test
    void rejectsFeaturePermutationEvenWithFreshFileDigest() throws Exception {
        fixture();
        var contract = JSON.readTree(Files.readAllBytes(directory.resolve("feature-contract.json")));
        String changed = JSON.writeValueAsString(contract).replace("\"intercept\",\"morning\"", "\"morning\",\"intercept\"");
        assertThat(changed).isNotEqualTo(JSON.writeValueAsString(contract));
        Files.writeString(directory.resolve("feature-contract.json"), changed);
        manifest();
        assertThatThrownBy(() -> LesInferenceBundle.load(directory)).hasMessageContaining("순서");
    }

    @Test
    void rejectsMissingStatisticsAndWrongTarget() throws Exception {
        fixture();
        Files.delete(directory.resolve("statistics-3330.json"));
        manifest();
        assertThatThrownBy(() -> LesInferenceBundle.load(directory)).hasMessageContaining("필수 파일");
        fixture();
        Files.writeString(directory.resolve("constants.json"), "{\"target\":\"arrive\",\"classes\":[0,1,2],\"middle\":1.3}");
        manifest();
        assertThatThrownBy(() -> LesInferenceBundle.load(directory)).hasMessageContaining("목표");
    }

    private void fixture() throws Exception {
        try (var input = getClass().getResourceAsStream("/les/feature-contract.json")) {
            Files.write(directory.resolve("feature-contract.json"), input.readAllBytes());
        }
        Files.writeString(directory.resolve("constants.json"), "{\"target\":\"after\",\"classes\":[0,1,2],\"middle\":1.3}");
        model("base", "regression", 88, List.of(), List.of(10));
        model("gate", "multiclass", 88, List.of(0, 1, 2), List.of(0, 0, 0));
        model("pool", "binary", 80, List.of(0, 1), List.of(0));
        Files.writeString(directory.resolve("statistics-manifest.json"), JSON.writeValueAsString(Map.of("schema", "les-statistics-v1", "routes", ROUTES)));
        Files.writeString(directory.resolve("route-reference.json"), JSON.writeValueAsString(Map.of("routes", ROUTES.stream().map(r -> Map.of("modelRoute", r)).toList())));
        for (String route : ROUTES) {
            Files.writeString(directory.resolve("statistics-" + route + ".json"), JSON.writeValueAsString(Map.of(
                "route", route, "min_per_stop", 1, "tables", Map.of(), "alighting", Map.of(), "route_metadata", List.of())));
        }
        manifest();
    }

    private void model(String name, String kind, int size, List<Integer> classes, List<Integer> baseline) throws Exception {
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
        try (var stream = Files.list(directory)) {
            for (Path path : stream.filter(p -> !p.getFileName().toString().equals("manifest.json")).toList()) {
                hashes.put(path.getFileName().toString(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
            }
        }
        Files.writeString(directory.resolve("manifest.json"), JSON.writeValueAsString(Map.of(
            "schema", "les-inference-bundle-v1", "model", "les-gate-base-and-pool", "serving_ready", false,
            "output_targets", List.of("after-alighting-estimated", "departure-zero"), "files", hashes)), StandardCharsets.UTF_8);
    }
}
