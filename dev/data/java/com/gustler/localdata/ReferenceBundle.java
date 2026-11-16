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
    static final String VERSION = "local-data-v4";
    static final String DIRECTORY = "/local/models/alighting-dev-v1";
    static final String DIGEST = "4d6f9c2fdea0eb869c57abde51b30c2f942cab66ef8f9fada9b4d9d5e0f6d172";
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(JsonNodeFeature.WRITE_PROPERTIES_SORTED)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    private static final Map<String, String> FILES = Map.ofEntries(
        Map.entry("base.json", "a387e84087e1e72f0e0f3093c97261e55218b63b60538b58942bf7fbafa36f3d"),
        Map.entry("boundaries.json", "273d74610750ad73e0f60423331e138bbdef110ec0ea0abf097bce27ec5ec2fc"),
        Map.entry("constants.json", "e42d6877e6d93a515826a2d74ce5474f4a72f8440c52f4ad12c618dc4bebe8da"),
        Map.entry("evidence-feature_parity.json", "1297a4670b768b140d7210e3699e1d5b45ed5cb1654e6275a0a770ee7d4f6f08"),
        Map.entry("evidence-runtime_validation.json",
            "39478d6ee5ceace482ddff152eff7c5a8c465f3b49c05f3725f6e11c3223e90a"),
        Map.entry("feature-contract.json", "2cbc9b7fc3c107d30204d0a79fbcedfba3b5a5c34283a1a28f28b23c7ec8c33c"),
        Map.entry("gate.json", "606a8d40f0d4e0b3631c01ecf2d3ab678d186f7410d00ce9b8aae7acf79c4672"),
        Map.entry("golden.json", "273d74610750ad73e0f60423331e138bbdef110ec0ea0abf097bce27ec5ec2fc"),
        Map.entry("manifest.json", "4d6f9c2fdea0eb869c57abde51b30c2f942cab66ef8f9fada9b4d9d5e0f6d172"),
        Map.entry("pool.json", "97974c682152982d3b8931e17c9f0cd6dabd4e97b1a9a14260db24b3e8a0456f"),
        Map.entry("route-reference.json", "6452362e0a464df864055920ef84cea2896bf2ecfb37df0f28221f697d71f2ee"),
        Map.entry("statistics-1650.json", "1b132654f71c5a0c008fee4a1b575ddf24d3887463f3f200a77853714661fc6c"),
        Map.entry("statistics-3000.json", "30a457f8cb77ca4cd1d0dac87de93b11c4c0944bdb003c29367768ada7ff87b0"),
        Map.entry("statistics-3330.json", "10e3f1399980b88799dbffdd6559323b281140a705f47587c543f73fc39462c7"),
        Map.entry("statistics-3500.json", "b7e8811bbbcc587e0b6f8f2bb478d0224025ac240337116310bd04fcb1790920"),
        Map.entry("statistics-5600.json", "9d26b063f44a8fbefde8b2b0374e932eb5b275ac40ec98318684d2faa2abe803"),
        Map.entry("statistics-6011.json", "f45a499f5a5b818f16447fea5521aff5397824f1b0c08ea58db4e25994fc7c51"),
        Map.entry("statistics-9007.json", "586a90c2548c50c1ffe8d27d843f40ad5c60360c3ecf735a91777c45ad2f3057"),
        Map.entry("statistics-9300.json", "46dfd42c7b1dac803438d8fa19d99845ce6988010b7aa20d938b5df6bba9f753"),
        Map.entry("statistics-manifest.json", "08e1faefb73613730951cd1c273db075be570d3441534993bb608d53f1e31984"),
        Map.entry("verification-feature_parity.json",
            "77d9842d4ec676a66163f1a370133c34c307ee1bca89e293fb1f716645e6d763"),
        Map.entry("verification-runtime_validation.json",
            "75b88a92cd3ccd8579dad38c17d5b6fb34683e6d02b2537a803ba67fcf3351cd"),
        Map.entry("verification-source-manifest.json",
            "5eaa2c3a593bbe36fa10eb96c83bf58678f0e4a4043289372a342223ab4ec496"));

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

    /** 저장소의 개발용 번들은 합성 번들이라, 개발 데이터 준비 도구는 합성 번들을 허용해 읽는다. */
    private static Inspection inspect(Path directory) {
        Inspection inspection = new FileModelBundleInspector(true).inspect(directory.toString());
        LocalData.require(DIGEST.equals(inspection.bundleDigest()), "MODEL_REFERENCE_IDENTITY");
        return inspection;
    }

    static byte[] bytes(Object value) { return JSON.writeValueAsBytes(value); }

    static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
