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
    static final String VERSION = "local-data-v2";
    static final String DIRECTORY = "/local/models/reference-20261002";
    static final String DIGEST = "f9d1b6d76d5ba4ef4b909a8903337cb9e7ba74883c7fe270432650f3f2fd80bb";
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(JsonNodeFeature.WRITE_PROPERTIES_SORTED)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    private static final Map<String, String> FILES = Map.of(
        "manifest.json", "392fd8ac62e0352eef02a4fe276d68745abe11510f0b7fa4aa933b5ca9820b4d",
        "weights.safetensors", "9211e027914c5b41589dc5c8bf404edf2567b464ce1eafebf6e1b5c9f0b6849d",
        "route-reference.json", "58b02e37dfe7b2b515effeed73104a49d816b73d8a9ab2dcb3ae6af1edffb23e");

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
