package com.gustler.backend.forecasting.infrastructure.bundle.alighting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.Mockito.mock;

import com.gustler.backend.forecasting.application.deployment.ModelBundleLoader;
import com.gustler.backend.forecasting.configuration.ForecastingModelConfiguration;
import com.gustler.backend.forecasting.domain.deployment.ModelDeploymentRepository;
import com.gustler.backend.forecasting.domain.deployment.ModelRelease;
import com.gustler.backend.forecasting.domain.model.RouteDirection;
import com.gustler.backend.forecasting.domain.model.RouteStop;
import com.gustler.backend.forecasting.domain.model.RouteStops;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import com.gustler.backend.forecasting.infrastructure.bundle.FileModelBundleInspector;
import com.gustler.backend.forecasting.infrastructure.bundle.FileModelBundleLoader;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 실제 관측 없이 만든 합성 번들을 어느 적재에서 받는지 검사한다. 번들은 재생·정산 검증을 실행하지 않았다고 밝히고,
 * 수치 대조와 후보 런타임 검증의 영수증만 싣는다.
 */
class SyntheticFixtureBundleTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> ROUTES = List.of("3330", "1650");
    private static final List<String> ORIGINAL_PROFILE_ROUTES =
        List.of("3330", "1650", "9007", "9300", "6011", "3000", "5600", "3500");
    private static final List<String> EXECUTED = List.of("feature_parity", "runtime_validation");
    private static final List<String> NOT_EXECUTED = List.of("historical_replay", "settlement");
    private static final String CAPACITY_POLICY = "observed-vehicle-prefix-max-through-closure-v2";

    @TempDir
    Path directory;

    @Test
    void 합성_번들을_허용한_적재기는_재생과_정산_영수증이_없는_번들을_확정_프로필로_읽는다() throws Exception {
        // given
        syntheticBundle(ROUTES, true, EXECUTED, NOT_EXECUTED);
        RouteStops stops = new RouteStops(1, "204000057",
            List.of(new RouteStop(1, 1, "s1", true, RouteDirection.UP)), "3330");

        // when
        ModelRelease release = new FileModelBundleLoader(true).filesUnder(directory.toString()).load();

        // then
        assertSoftly(softly -> {
            softly.assertThat(release.releaseId()).isEqualTo("test-synthetic");
            softly.assertThat(release.scope().modelRoutes()).containsExactlyElementsOf(ROUTES);
            softly.assertThat(release.model().statistics(stops).confirmedProfile()).isTrue();
            softly.assertThat(new FileModelBundleInspector(true).inspect(directory.toString()).bundleDigest())
                .isEqualTo(release.bundleDigest());
        });
    }

    @Test
    void 합성_번들을_허용하지_않은_적재기는_합성_번들을_거절한다() throws Exception {
        // given
        syntheticBundle(ROUTES, true, EXECUTED, NOT_EXECUTED);

        // then
        assertSoftly(softly -> {
            softly.assertThatThrownBy(() -> new FileModelBundleLoader().filesUnder(directory.toString()).load())
                .hasRootCauseMessage("합성 번들은 로컬 개발 적재에서만 받는다");
            softly.assertThatThrownBy(() -> new FileModelBundleInspector().inspect(directory.toString()))
                .hasRootCauseMessage("합성 번들은 로컬 개발 적재에서만 받는다");
            softly.assertThatThrownBy(() -> AlightingAwareInferenceBundle.loadForServing(directory))
                .hasMessage("합성 번들은 로컬 개발 적재에서만 받는다");
        });
    }

    @Test
    void 구성은_설정을_켰을_때만_합성_번들을_받는_적재기를_만든다() throws Exception {
        // given
        syntheticBundle(ROUTES, true, EXECUTED, NOT_EXECUTED);
        ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ForecastingModelConfiguration.class)
            .withBean(ModelDeploymentRepository.class, () -> mock(ModelDeploymentRepository.class));

        // then
        runner.run(context -> assertThatThrownBy(() -> context.getBean(ModelBundleLoader.class)
            .filesUnder(directory.toString()).load())
            .hasRootCauseMessage("합성 번들은 로컬 개발 적재에서만 받는다"));
        runner.withPropertyValues("model.bundle.allow-synthetic-fixture=true")
            .run(context -> assertThat(context.getBean(ModelBundleLoader.class)
                .filesUnder(directory.toString()).load().releaseId()).isEqualTo("test-synthetic"));
    }

    @Test
    void 합성_번들_표시가_없으면_허용한_적재기도_검증_생략을_받지_않는다() throws Exception {
        // given
        syntheticBundle(ROUTES, true, EXECUTED, NOT_EXECUTED);
        editManifest(manifest -> manifest.put("synthetic_fixture", false));

        // then
        assertThatThrownBy(() -> AlightingAwareInferenceBundle.loadForServing(directory, true))
            .hasMessage("합성 번들 표시가 없다");

        // when
        editManifest(manifest -> manifest.remove("synthetic_fixture"));

        // then
        assertThatThrownBy(() -> AlightingAwareInferenceBundle.loadForServing(directory, true))
            .hasMessage("합성 번들 표시가 없다");
    }

    @ParameterizedTest
    @MethodSource("unacceptableSelections")
    void 재생과_정산이_아닌_검증이나_빈_목록은_생략으로_받지_않는다(List<String> unverified) throws Exception {
        // given
        List<String> executed = new ArrayList<>(List.of("feature_parity", "historical_replay", "settlement",
            "runtime_validation"));
        executed.removeAll(unverified);
        syntheticBundle(ROUTES, true, executed, unverified);

        // then
        assertThatThrownBy(() -> AlightingAwareInferenceBundle.loadForServing(directory, true))
            .hasMessage("생략할 수 없는 검증이다");
    }

    private static Stream<List<String>> unacceptableSelections() {
        return Stream.of(List.of(), List.of("feature_parity"), List.of("historical_replay", "runtime_validation"),
            List.of("settlement", "settlement"));
    }

    @Test
    void 실행하지_않았다고_밝힌_검증의_영수증이_번들에_있으면_거절한다() throws Exception {
        // given
        syntheticBundle(ROUTES, true, List.of("feature_parity", "runtime_validation", "historical_replay"),
            NOT_EXECUTED);

        // then
        assertThatThrownBy(() -> AlightingAwareInferenceBundle.loadForServing(directory, true))
            .hasMessage("실행하지 않은 검증의 영수증이 있다: historical_replay");
    }

    @Test
    void 확정_입력_계약이_아닌_번들은_검증_생략을_밝혀도_받지_않는다() throws Exception {
        // given
        syntheticBundle(ORIGINAL_PROFILE_ROUTES, false, EXECUTED, NOT_EXECUTED);

        // then
        assertThatThrownBy(() -> AlightingAwareInferenceBundle.loadForServing(directory, true))
            .hasMessage("합성 번들도 확정 입력 계약을 따라야 한다");
    }

    @Test
    void 생략하지_않은_검증은_합성_번들에서도_증거_내용까지_검사한다() throws Exception {
        // given
        syntheticBundle(ROUTES, true, EXECUTED, NOT_EXECUTED);
        ObjectNode evidence = (ObjectNode) JSON.readTree(Files.readAllBytes(
            directory.resolve("evidence-runtime_validation.json")));
        ((ObjectNode) evidence.get("result")).set("routes", JSON.valueToTree(List.of("3330")));
        Files.writeString(directory.resolve("evidence-runtime_validation.json"), JSON.writeValueAsString(evidence));
        ObjectNode receipt = (ObjectNode) JSON.readTree(Files.readAllBytes(
            directory.resolve("verification-runtime_validation.json")));
        receipt.put("evidence_sha256", sha(directory.resolve("evidence-runtime_validation.json")));
        Files.writeString(directory.resolve("verification-runtime_validation.json"),
            JSON.writeValueAsString(receipt));
        editManifest(manifest -> {
            ObjectNode files = (ObjectNode) manifest.get("files");
            files.put("evidence-runtime_validation.json", sha(directory.resolve("evidence-runtime_validation.json")));
            files.put("verification-runtime_validation.json",
                sha(directory.resolve("verification-runtime_validation.json")));
        });

        // then
        assertThatThrownBy(() -> AlightingAwareInferenceBundle.loadForServing(directory, true))
            .hasMessage("런타임 검증 노선이 다르다");
    }

    @Test
    void 한쪽_검증만_실행하지_않았다고_밝힌_번들은_다른_쪽_영수증이_있어야_한다() throws Exception {
        // given
        syntheticBundle(ROUTES, true, EXECUTED, List.of("settlement"));

        // then
        assertThatThrownBy(() -> AlightingAwareInferenceBundle.loadForServing(directory, true))
            .hasMessage("필수 파일이 없다: verification-historical_replay.json");
    }

    @Test
    void 합성_번들을_허용해도_검증_생략을_밝히지_않은_번들은_네_영수증을_모두_요구한다() throws Exception {
        // given
        syntheticBundle(ROUTES, true, EXECUTED, NOT_EXECUTED);
        editManifest(manifest -> ((ObjectNode) manifest.get("runtime")).remove("unverified_checks"));

        // then
        assertThatThrownBy(() -> new FileModelBundleLoader(true).filesUnder(directory.toString()).load())
            .hasRootCauseMessage("필수 파일이 없다: verification-historical_replay.json");
    }

    /**
     * 원본 번들, 실행한 검증의 영수증·증거, 서빙 manifest를 차례로 쓴다.
     *
     * @param confirmed 확정 입력 계약 필드를 계약·통계·영수증·서빙 계약에 넣을지
     * @param executed 영수증과 증거를 싣는 검증
     * @param unverified 실행하지 않았다고 밝히는 검증
     */
    private void syntheticBundle(
        List<String> routes,
        final boolean confirmed,
        List<String> executed,
        List<String> unverified
    ) throws Exception {
        writeSource(routes, confirmed);
        byte[] source = Files.readAllBytes(directory.resolve("manifest.json"));
        String sourceDigest = sha(source);
        for (String check : executed) {
            writeReceipt(check, sourceDigest, routes, confirmed);
        }
        Files.write(directory.resolve("verification-source-manifest.json"), source);
        ObjectNode manifest = (ObjectNode) JSON.readTree(source);
        manifest.put("serving_ready", true);
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("release_id", "test-synthetic");
        runtime.put("feature_contract_version", "alighting-online-v1");
        runtime.put("data_through", "2026-10-21T00:00:00Z");
        runtime.put("source_manifest_sha256", sourceDigest);
        runtime.put("routes", routes);
        runtime.put("scope", "weekday-rush-6-10-17-21-horizon-1-12");
        runtime.put("unverified_checks", unverified);
        if (confirmed) {
            runtime.putAll(contract());
        }
        manifest.set("runtime", JSON.valueToTree(runtime));
        manifest.set("files", JSON.valueToTree(fileDigests()));
        Files.writeString(directory.resolve("manifest.json"), JSON.writeValueAsString(manifest));
    }

    private void writeSource(List<String> routes, final boolean confirmed) throws Exception {
        ObjectNode contract;
        try (InputStream input = getClass().getResourceAsStream("/alighting/feature-contract.json")) {
            contract = (ObjectNode) JSON.readTree(input.readAllBytes());
        }
        if (confirmed) {
            contract().forEach(contract::put);
        }
        Files.writeString(directory.resolve("feature-contract.json"), JSON.writeValueAsString(contract));
        Files.writeString(directory.resolve("constants.json"),
            "{\"target\":\"after\",\"classes\":[0,1,2],\"middle\":1.3}");
        writeModel("base", "regression", 88, List.of(), List.of(10));
        writeModel("gate", "multiclass", 88, List.of(0, 1, 2), List.of(0, 0, 0));
        writeModel("pool", "binary", 80, List.of(0, 1), List.of(0));
        Files.writeString(directory.resolve("statistics-manifest.json"), JSON.writeValueAsString(
            Map.of("schema", "alighting-statistics-v1", "routes", routes, "holiday_dates", List.of())));
        Files.writeString(directory.resolve("route-reference.json"), JSON.writeValueAsString(
            Map.of("routes", routes.stream().map(route -> Map.of("modelRoute", route)).toList())));
        for (String route : routes) {
            Files.writeString(directory.resolve("statistics-" + route + ".json"),
                JSON.writeValueAsString(statistics(route, confirmed)));
        }
        Map<String, Object> golden = Map.of("seat_inputs", List.of(new double[88]), "probability_inputs",
            List.of(new double[80]), "expected_base", List.of(10),
            "expected_gate_probabilities", List.of(List.of(1.0 / 3, 1.0 / 3, 1.0 / 3)),
            "expected_seats", List.of((1.3 + 10) / 3), "expected_departure_full", List.of(0.5));
        Files.writeString(directory.resolve("golden.json"), JSON.writeValueAsString(golden));
        Files.writeString(directory.resolve("boundaries.json"), JSON.writeValueAsString(golden));
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schema", "alighting-inference-bundle-v1");
        manifest.put("model", "alighting-gate-base-and-pool");
        manifest.put("serving_ready", false);
        manifest.put("synthetic_fixture", true);
        manifest.put("output_targets", List.of("after-alighting-estimated", "departure-zero"));
        manifest.put("files", fileDigests());
        Files.writeString(directory.resolve("manifest.json"), JSON.writeValueAsString(manifest));
    }

    private static Map<String, Object> statistics(String route, final boolean confirmed) {
        Map<String, Object> statistics = new LinkedHashMap<>();
        statistics.put("route", route);
        statistics.put("min_per_stop", 1);
        statistics.put("tables", Map.of("lam", List.of(), "lam_base", List.of(), "net_tab", List.of(),
            "full_tab", List.of(), "dep_tab", List.of(), "stop_net_base", List.of(), "target_band", List.of()));
        statistics.put("alighting", Map.of("stops", List.of()));
        statistics.put("route_metadata", List.of(
            Map.of("order", 1, "id", "s1", "name", "정류장1", "direction", "UP", "boardingAllowed", true)));
        statistics.put("events_known_through", "2026-10-21T00:00:00Z");
        statistics.put("observations_through", "2026-10-21T00:00:00Z");
        if (confirmed) {
            statistics.put("input_profile", AlightingAwareRouteStatistics.CONFIRMED_PROFILE);
        }
        return statistics;
    }

    private void writeReceipt(
        String check,
        String sourceDigest,
        List<String> routes,
        final boolean confirmed
    ) throws Exception {
        Map<String, Object> result = result(check, sourceDigest, routes);
        final int cases = verifiedCases(check, routes);
        Path evidenceFile = directory.resolve("evidence-" + check + ".json");
        Files.writeString(evidenceFile, JSON.writeValueAsString(Map.of(
            "schema", "alighting-confirmed-serving-evidence-v1", "routes", routes, "verified_cases", cases,
            "result", result)));
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("schema", "alighting-serving-verification-v1");
        receipt.put("check", check);
        receipt.put("status", "PASS");
        receipt.put("source_manifest_sha256", sourceDigest);
        receipt.put("feature_contract_version", "alighting-online-v1");
        receipt.put("verified_cases", cases);
        receipt.put("evidence_file", "evidence-" + check + ".json");
        receipt.put("evidence_sha256", sha(evidenceFile));
        receipt.put("routes", routes);
        if (confirmed) {
            receipt.putAll(contract());
        }
        Files.writeString(directory.resolve("verification-" + check + ".json"), JSON.writeValueAsString(receipt));
    }

    private static Map<String, Object> result(String check, String sourceDigest, List<String> routes) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("passed", true);
        result.put("bundle_manifest_sha256", sourceDigest);
        if (check.equals("feature_parity")) {
            Map<String, Object> comparison = Map.of("rows", 1, "max_absolute_difference", new double[6]);
            result.put("golden.json", comparison);
            result.put("boundaries.json", comparison);
            return result;
        }
        if (check.equals("runtime_validation")) {
            result.put("routes", routes);
            result.put("input_profile", AlightingAwareRouteStatistics.CONFIRMED_PROFILE);
            result.put("mode", "candidate");
            result.put("verified_cases", routes.size());
            return result;
        }
        result.put("routes", routes.stream().map(route -> Map.of("route", route, "passed", true, "cases", 1,
            "settled_targets", 2, "failures", List.of(), "max_feature_difference", 0,
            "max_prediction_difference", 0, "max_label_difference", 0)).toList());
        return result;
    }

    private static int verifiedCases(String check, List<String> routes) {
        if (check.equals("feature_parity")) {
            return 2;
        }
        if (check.equals("settlement")) {
            return routes.size() * 2;
        }
        return routes.size();
    }

    private static Map<String, String> contract() {
        Map<String, String> contract = new LinkedHashMap<>();
        contract.put("input_profile", AlightingAwareRouteStatistics.CONFIRMED_PROFILE);
        contract.put("label_version", AlightingAwareRouteStatistics.CONFIRMED_LABEL);
        contract.put("capacity_policy", CAPACITY_POLICY);
        return contract;
    }

    private void writeModel(
        String name,
        String kind,
        final int size,
        List<Integer> classes,
        List<Integer> baseline
    ) throws Exception {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schema", "alighting-numeric-hgb-v1");
        value.put("kind", kind);
        value.put("classes", classes);
        value.put("feature_count", size);
        value.put("baseline", baseline);
        value.put("leaf_values_include_learning_rate", true);
        value.put("node_fields", List.of("feature", "threshold", "missing_left", "left", "right", "value", "leaf"));
        value.put("trees", List.of(baseline.stream()
            .map(ignored -> List.of(List.of(0, 0, false, 0, 0, 0, true)))
            .toList()));
        Files.writeString(directory.resolve(name + ".json"), JSON.writeValueAsString(value));
    }

    private void editManifest(ManifestEdit edit) throws Exception {
        Path file = directory.resolve("manifest.json");
        ObjectNode manifest = (ObjectNode) JSON.readTree(Files.readAllBytes(file));
        edit.apply(manifest);
        Files.writeString(file, JSON.writeValueAsString(manifest));
    }

    private Map<String, String> fileDigests() throws Exception {
        Map<String, String> digests = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.list(directory)) {
            for (Path path : paths.filter(file -> !file.getFileName().toString().equals("manifest.json")).toList()) {
                digests.put(path.getFileName().toString(), sha(path));
            }
        }
        return digests;
    }

    private static String sha(Path file) throws Exception {
        return sha(Files.readAllBytes(file));
    }

    private static String sha(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    @FunctionalInterface
    private interface ManifestEdit {
        void apply(ObjectNode manifest) throws Exception;
    }
}
