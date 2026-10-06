package com.gustler.localdata;

import com.gustler.backend.forecasting.api.model.InspectModelBundle.Inspection;
import com.gustler.backend.forecasting.domain.model.ForecastFeatureContract;
import com.gustler.backend.forecasting.domain.model.HorizonCoefficients;
import com.gustler.backend.forecasting.domain.model.SeatDistributionInput;
import com.gustler.backend.forecasting.domain.model.SeatDistributionPredictor;
import com.gustler.backend.forecasting.infrastructure.bundle.FileModelBundleInspector;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

final class DevelopmentBundle {
    static final String VERSION = "local-data-v1";
    static final String DATA_THROUGH = "2026-10-06T00:00:00Z";
    private static final String SOURCE_COMMIT = "43c0416bc1b75ca529e53efeb06416f512278e8f";
    private static final ForecastFeatureContract FEATURES = ForecastFeatureContract.STOP_DIRECTION_TIME_STATISTICS;
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(JsonNodeFeature.WRITE_PROPERTIES_SORTED)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    static Inspection prepare(RouteDataset data, Path directory) throws Exception {
        LocalData.require(!Files.isSymbolicLink(directory.getParent()) && !Files.isSymbolicLink(directory), "MODEL_DIRECTORY");
        Files.createDirectories(directory.getParent());
        Map<String, byte[]> files = files(data);
        if (Files.exists(directory)) {
            for (var file : files.entrySet()) {
                Path path = directory.resolve(file.getKey());
                LocalData.require(!Files.isSymbolicLink(path) && Files.isRegularFile(path)
                    && Arrays.equals(Files.readAllBytes(path), file.getValue()), "MODEL_FIXTURE_CHANGED");
            }
        } else {
            Path stage = Files.createTempDirectory(directory.getParent(), ".prepare-");
            try {
                for (var file : files.entrySet()) {
                    Files.write(stage.resolve(file.getKey()), file.getValue());
                }
                new FileModelBundleInspector().inspect(stage.toString());
                Files.move(stage, directory, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                if (Files.exists(stage)) {
                    for (String name : files.keySet()) {
                        Files.deleteIfExists(stage.resolve(name));
                    }
                    Files.delete(stage);
                }
            }
        }
        return new FileModelBundleInspector().inspect(directory.toString());
    }

    private static Map<String, byte[]> files(RouteDataset data) throws Exception {
        byte[] reference = bytes(Map.of("version", "local-route-reference-v1", "routes",
            data.routes().stream().map(route -> Map.of("modelRoute", route.displayName(), "sourceRouteId", route.routeId(),
                "stops", route.stops().stream().map(stop -> Map.of("order", stop.sequence(), "id", stop.stationId(),
                    "boardingAllowed", stop.boardingAllowed(), "direction", stop.direction())).toList())).toList()));
        final int features = FEATURES.featureNames().size();
        Map<String, int[]> shapes = new LinkedHashMap<>();
        shapes.put("hurdle_coefficients", new int[] {8, 12, features});
        shapes.put("anchor_coefficients", new int[] {8, 12, 2});
        shapes.put("sign_coefficients", new int[] {8, 12, 2, features});
        shapes.put("bin_coefficients", new int[] {8, 12, 2, 9, features});
        shapes.put("bin_fitted", new int[] {8, 12, 2, 9});
        Map<String, Object> header = new LinkedHashMap<>();
        Map<String, Object> declarations = new LinkedHashMap<>();
        int offset = 0;
        for (var entry : shapes.entrySet()) {
            String dtype = entry.getKey().equals("bin_fitted") ? "U8" : "F64";
            final int size = Arrays.stream(entry.getValue()).reduce(1, (a, b) -> a * b) * (dtype.equals("U8") ? 1 : 8);
            List<Integer> shape = Arrays.stream(entry.getValue()).boxed().toList();
            header.put(entry.getKey(), Map.of("dtype", dtype, "shape", shape, "data_offsets", List.of(offset, offset + size)));
            declarations.put(entry.getKey(), Map.of("dtype", dtype, "shape", shape));
            offset += size;
        }
        byte[] headerBytes = bytes(header);
        final int paddedSize = ((headerBytes.length + 7) / 8) * 8;
        ByteBuffer weights = ByteBuffer.allocate(8 + paddedSize + offset).order(ByteOrder.LITTLE_ENDIAN);
        weights.putLong(paddedSize).put(headerBytes);
        while (weights.position() < 8 + paddedSize) {
            weights.put((byte) ' ');
        }
        for (int route = 0; route < 8; route++) {
            for (int horizon = 1; horizon <= 12; horizon++) {
                for (double coefficient : hurdle(route, horizon)) {
                    weights.putDouble(coefficient);
                }
            }
        }
        var coefficients = new HorizonCoefficients(hurdle(0, 1), new double[2], new double[features],
            new double[features], new double[2][9][features], new boolean[2][9]);
        var predictor = new SeatDistributionPredictor((route, horizon) -> coefficients,
            new double[] {0, .03, .07, .12, .2, .32, .48, .7, 1});
        double[] vector = new double[features];
        vector[0] = 1;
        vector[4] = .25;
        vector[28] = .5;
        vector[29] = -.25;
        var distribution = predictor.predict(new SeatDistributionInput(vector, "1650", 1, 10, 40, null)).distribution();
        final double fullChance = distribution.fullChance();
        final double expectedSeats = distribution.expectedSeats();
        String goldenDigest = sha(String.join("\n",
            Arrays.stream(vector).mapToObj(Double::toString).collect(Collectors.joining(",")), "1650", "1", "10", "40",
            Double.toString(fullChance), Double.toString(expectedSeats)).getBytes(StandardCharsets.UTF_8));
        String weightsDigest = sha(weights.array());
        String referenceDigest = sha(reference);
        String timeSource = "observation_batch.response_received_at;Asia/Seoul;new_time_slot=0";
        String capacityPolicy = "maximum-seats-ever-observed";
        String identity = sha(String.join("\n", ForecastFeatureContract.STATISTICS_VERSION, SOURCE_COMMIT,
            "seat-distribution-a18-v1", "local-route-reference-v1", referenceDigest, weightsDigest,
            String.join(",", FEATURES.featureNames()), "largestSeatCount=68.0,lowSeatBandWidth=20.0",
            timeSource, capacityPolicy, ForecastFeatureContract.STATISTICS_POLICY, goldenDigest).getBytes(StandardCharsets.UTF_8));
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("bundleSchemaVersion", "a18-live-bundle-v2");
        manifest.put("modelVersion", "seat-distribution-a18-v1");
        manifest.put("releaseId", "local-development-v1");
        manifest.put("featureContractVersion", ForecastFeatureContract.STATISTICS_VERSION);
        manifest.put("sourceCommit", SOURCE_COMMIT);
        manifest.put("routeReference", Map.of("version", "local-route-reference-v1", "digest", referenceDigest));
        manifest.put("routes", RouteDataset.MODEL_ROUTES);
        manifest.put("horizonStops", IntStream.rangeClosed(1, 12).boxed().toList());
        manifest.put("featureNames", FEATURES.featureNames());
        manifest.put("normalizationConstants", Map.of("largestSeatCount", 68.0, "lowSeatBandWidth", 20.0));
        manifest.put("timeSlotSource", timeSource);
        manifest.put("capacityPolicy", capacityPolicy);
        manifest.put("cellStatisticsPolicy", ForecastFeatureContract.STATISTICS_POLICY);
        manifest.put("tensors", declarations);
        manifest.put("weightsDigest", weightsDigest);
        manifest.put("goldenVectorDigest", goldenDigest);
        manifest.put("identityDigest", identity);
        manifest.put("dataThrough", DATA_THROUGH);
        manifest.put("goldenVector", Map.of("modelRoute", "1650", "stopsAhead", 1, "currentSeats", 10, "capacity", 40,
            "featureVector", Arrays.stream(vector).boxed().toList(), "expectedFullChance", fullChance, "expectedSeats", expectedSeats));
        return Map.of("manifest.json", bytes(manifest), "weights.safetensors", weights.array(), "route-reference.json", reference);
    }

    private static double[] hurdle(final int route, final int horizon) {
        double[] values = new double[FEATURES.featureNames().size()];
        values[0] = -.4 + route * .06 + horizon * .03;
        values[4] = -3;
        values[5] = 1.5;
        values[28] = 1.2;
        values[29] = .8;
        values[31] = .3;
        return values;
    }

    static byte[] bytes(Object value) {
        return JSON.writeValueAsBytes(value);
    }

    static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
