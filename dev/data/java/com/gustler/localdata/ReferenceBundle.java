package com.gustler.localdata;

import com.gustler.backend.forecasting.api.model.InspectModelBundle.Inspection;
import com.gustler.backend.forecasting.infrastructure.bundle.FileModelBundleInspector;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

final class ReferenceBundle {
    static final String VERSION = "local-data-v3";
    static final String DIRECTORY = "/local/models/alighting-dev-v1";
    static final String DIGEST = "271d569083f66aa3cb9d3693ac2e9d3a76ca71fcc53b23471e57ce6a14966dcd";
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(JsonNodeFeature.WRITE_PROPERTIES_SORTED)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    private static final Map<String, String> FILES = Map.ofEntries(
        Map.entry("base.json", "a387e84087e1e72f0e0f3093c97261e55218b63b60538b58942bf7fbafa36f3d"),
        Map.entry("boundaries.json", "273d74610750ad73e0f60423331e138bbdef110ec0ea0abf097bce27ec5ec2fc"),
        Map.entry("constants.json", "e42d6877e6d93a515826a2d74ce5474f4a72f8440c52f4ad12c618dc4bebe8da"),
        Map.entry("evidence-feature_parity.json", "e37f9efdd474950a8bd9356607c00b3e58648a76d587f0a8133bdea9c252f786"),
        Map.entry("evidence-historical_replay.json", "e37f9efdd474950a8bd9356607c00b3e58648a76d587f0a8133bdea9c252f786"),
        Map.entry("evidence-runtime_validation.json", "e37f9efdd474950a8bd9356607c00b3e58648a76d587f0a8133bdea9c252f786"),
        Map.entry("evidence-settlement.json", "e37f9efdd474950a8bd9356607c00b3e58648a76d587f0a8133bdea9c252f786"),
        Map.entry("feature-contract.json", "a4653a0d8ebdc76f3a672233dae2682fe94e2006fec6cb67889d56ddbcb043d6"),
        Map.entry("gate.json", "606a8d40f0d4e0b3631c01ecf2d3ab678d186f7410d00ce9b8aae7acf79c4672"),
        Map.entry("golden.json", "273d74610750ad73e0f60423331e138bbdef110ec0ea0abf097bce27ec5ec2fc"),
        Map.entry("manifest.json", "271d569083f66aa3cb9d3693ac2e9d3a76ca71fcc53b23471e57ce6a14966dcd"),
        Map.entry("pool.json", "97974c682152982d3b8931e17c9f0cd6dabd4e97b1a9a14260db24b3e8a0456f"),
        Map.entry("route-reference.json", "c56ea26e5bafddd74df19448a67576c8767a6aaf126d92e9810572e58a68310d"),
        Map.entry("statistics-1650.json", "240e670a2bbcc15fc5d7686e6749f880e218a3c7915582e0e3e20f79009c2915"),
        Map.entry("statistics-3000.json", "8ca3ae6cbc585011cbb5eae9bc69e5d464908650292bea45fa854559ad16f19f"),
        Map.entry("statistics-3330.json", "4e52f7a7415c324f9a80873a9b93a9d3d964a3d431b337ff6d52fc62cd1b6cb0"),
        Map.entry("statistics-3500.json", "504b399923d47b684d43358f49ce6ee6759dfb4aa3282ebf49dae0d0b4cd1a5c"),
        Map.entry("statistics-5600.json", "4782c9f48cf65354e6d8f0d4232f13a3c6e64f375b8a87937b86e78d17db0635"),
        Map.entry("statistics-6011.json", "09215715bee9f3f7cc39bdcc2a477364b1d3c4b8fb16e9344f042aacd5d0ee87"),
        Map.entry("statistics-9007.json", "acf5389e28bfcab6f29179f344c606db867018f4e2f5d19c60654f7c1cd146ca"),
        Map.entry("statistics-9300.json", "22402dbf06446632ca6fe20a9e7312db0fb7a8d8338f8387e01d541e2b4a3cd4"),
        Map.entry("statistics-manifest.json", "08e1faefb73613730951cd1c273db075be570d3441534993bb608d53f1e31984"),
        Map.entry("verification-feature_parity.json", "7c2fcf150838d5dcbd2f86df085f30ff28959a668672a5a19acec26309a2f314"),
        Map.entry("verification-historical_replay.json", "6e37de4e8b207cfd42801bbd76cbe557ddf971ff7a2bead564a1c6acaab8d45b"),
        Map.entry("verification-runtime_validation.json", "9a23aca0f427f81775e498c18918407c309fa1963782bd6a37943ba7754d8f1e"),
        Map.entry("verification-settlement.json", "4e0ddb526c68203e4ce71a3c9ee49444b6656b1229d83c0cd75c8b18512169e6"),
        Map.entry("verification-source-manifest.json", "8991ffd90ee84c11f3fbf51f42347d05ebba60485c07b5b218ea225f2b776b33"));

    static Inspection prepare(Path source, Path directory) throws Exception {
        LocalData.require(!Files.isSymbolicLink(source) && !Files.isSymbolicLink(directory.getParent())
            && !Files.isSymbolicLink(directory), "MODEL_DIRECTORY");
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (var entry : FILES.entrySet()) {
            Path file = source.resolve(entry.getKey());
            LocalData.require(!Files.isSymbolicLink(file) && Files.isRegularFile(file)
                && Files.size(file) <= 10_485_760, "MODEL_REFERENCE_FILE");
            byte[] bytes = Files.readAllBytes(file);
            LocalData.require(sha(bytes).equals(entry.getValue()), "MODEL_REFERENCE_CHANGED");
            files.put(entry.getKey(), bytes);
        }
        Files.createDirectories(directory.getParent());
        if (Files.exists(directory)) {
            for (var entry : files.entrySet()) {
                Path file = directory.resolve(entry.getKey());
                LocalData.require(!Files.isSymbolicLink(file) && Files.isRegularFile(file)
                    && Arrays.equals(Files.readAllBytes(file), entry.getValue()), "MODEL_FIXTURE_CHANGED");
            }
        } else {
            Path stage = Files.createTempDirectory(directory.getParent(), ".reference-");
            try {
                for (var entry : files.entrySet()) { Files.write(stage.resolve(entry.getKey()), entry.getValue()); }
                inspect(stage);
                Files.move(stage, directory, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                if (Files.exists(stage)) {
                    for (String name : files.keySet()) { Files.deleteIfExists(stage.resolve(name)); }
                    Files.delete(stage);
                }
            }
        }
        return inspect(directory);
    }

    private static Inspection inspect(Path directory) {
        var inspection = new FileModelBundleInspector().inspect(directory.toString());
        LocalData.require(DIGEST.equals(inspection.bundleDigest()), "MODEL_REFERENCE_IDENTITY");
        return inspection;
    }

    static byte[] bytes(Object value) { return JSON.writeValueAsBytes(value); }

    static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
