package com.gustler.backend.maintenance;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 모델 정확도와 무관한 상수 트리의 프로세스 점검용 합성 번들이다. */
final class BundleInspectionFixture {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> ROUTES = List.of("3330", "1650", "9007", "9300", "6011", "3000", "5600", "3500");
    private final Path directory;
    private BundleInspectionFixture(Path directory) { this.directory=directory; }
    static void write(Path directory) throws Exception {
        Files.createDirectories(directory);
        var fixture = new BundleInspectionFixture(directory);
        fixture.fixture();
        fixture.servingFixture((1.3+10)/3);
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
                "schema", "alighting-serving-verification-v1", "check", check, "status", "PASS", "verified_cases", 1,
                "source_manifest_sha256", sourceHash, "feature_contract_version", "alighting-online-v1",
                "evidence_file", "evidence-" + check + ".json", "evidence_sha256", hash)));
        }
        manifest();
        ObjectNode root = (ObjectNode) JSON.readTree(Files.readAllBytes(directory.resolve("manifest.json")));
        root.put("serving_ready", true);
        root.set("runtime",
            JSON.valueToTree(Map.of("release_id", "test-alighting", "feature_contract_version", "alighting-online-v1",
                "data_through", "2026-10-21T00:00:00Z", "source_manifest_sha256", sourceHash, "routes", ROUTES,
                "scope", "weekday-rush-6-10-17-21-horizon-1-12")));
        Files.writeString(directory.resolve("manifest.json"), JSON.writeValueAsString(root));
    }

    private void fixture() throws Exception {
        fixture(ROUTES);
    }

    private void fixture(List<String> routes) throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/alighting/feature-contract.json")) {
            Files.write(directory.resolve("feature-contract.json"), input.readAllBytes());
        }
        Files.writeString(directory.resolve("constants.json"),
            "{\"target\":\"after\",\"classes\":[0,1,2],\"middle\":1.3}");
        model("base", "regression", 88, List.of(), List.of(10));
        model("gate", "multiclass", 88, List.of(0, 1, 2), List.of(0, 0, 0));
        model("pool", "binary", 80, List.of(0, 1), List.of(0));
        Files.writeString(directory.resolve("statistics-manifest.json"),
            JSON.writeValueAsString(Map.of("schema", "alighting-statistics-v1", "routes", routes, "holiday_dates", List.of())));
        Files.writeString(directory.resolve("route-reference.json"),
            JSON.writeValueAsString(Map.of("routes", routes.stream().map(r -> Map.of("modelRoute", r)).toList())));
        for (String route : routes) {
            Files.writeString(directory.resolve("statistics-" + route + ".json"), JSON.writeValueAsString(Map.of(
                "route", route, "min_per_stop", 1, "tables", Map.of("lam",List.of(),"lam_base",List.of(),
                    "net_tab",List.of(),"full_tab",List.of(),"dep_tab",List.of(),"stop_net_base",List.of(),
                    "target_band",List.of()), "alighting", Map.of("stops",List.of()), "route_metadata",
                List.of(Map.of("order",1,"id","s1","name","정류장1","direction","UP","boardingAllowed",true)),
                "events_known_through", "2026-10-21T00:00:00Z", "observations_through", "2026-10-21T00:00:00Z")));
        }
        manifest();
    }

    private void model(
        String name,
        String kind,
        final int size,
        List<Integer> classes,
        List<Integer> baseline
    )
        throws Exception {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schema", "alighting-numeric-hgb-v1");
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
            "schema", "alighting-inference-bundle-v1", "model", "alighting-gate-base-and-pool", "serving_ready", false,
            "output_targets", List.of("after-alighting-estimated", "departure-zero"), "files", hashes)),
            StandardCharsets.UTF_8);
    }
}
