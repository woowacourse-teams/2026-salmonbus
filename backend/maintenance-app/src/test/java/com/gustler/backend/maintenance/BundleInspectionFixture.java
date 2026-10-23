package com.gustler.backend.maintenance;

import com.gustler.backend.forecasting.domain.model.HorizonCoefficients;
import com.gustler.backend.forecasting.domain.model.SeatDistributionInput;
import com.gustler.backend.forecasting.domain.model.SeatDistributionPredictor;
import com.gustler.backend.forecasting.domain.model.SeatForecastDesignMatrix;
import com.gustler.backend.forecasting.domain.model.SeatForecastResult;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** 원 관측이나 학습 계수 없이 만드는 0 계수의 형식 검증용 합성 자료. */
final class BundleInspectionFixture {
    private static final List<String> ROUTES = List.of("1650", "3330", "9007", "9300", "6011", "3000", "5600", "3500");

    static void write(Path directory) throws Exception {
        Files.createDirectories(directory);
        final int features = SeatForecastDesignMatrix.COLUMN_COUNT;
        Map<String, int[]> shapes = new LinkedHashMap<>();
        shapes.put("hurdle_coefficients", new int[] {8, 12, features});
        shapes.put("anchor_coefficients", new int[] {8, 12, 2});
        shapes.put("sign_coefficients", new int[] {8, 12, 2, features});
        shapes.put("bin_coefficients", new int[] {8, 12, 2, 9, features});
        shapes.put("bin_fitted", new int[] {8, 12, 2, 9});
        Map<String, Object> header = new LinkedHashMap<>();
        Map<String, Object> declarations = new LinkedHashMap<>();
        int offset = 0;
        for (Entry<String, int[]> entry : shapes.entrySet()) {
            String dtype = tensorDataType(entry.getKey());
            final int bytes = Arrays.stream(entry.getValue())
                .reduce(1, (a, b) -> a * b) * elementBytes(dtype);
            List<Integer> shape = Arrays.stream(entry.getValue())
                .boxed()
                .toList();
            header.put(entry.getKey(),
                Map.of("dtype", dtype, "shape", shape, "data_offsets", List.of(offset, offset + bytes)));
            declarations.put(entry.getKey(), Map.of("dtype", dtype, "shape", shape));
            offset += bytes;
        }
        byte[] headerBytes = CanonicalJson.stringOf(header).getBytes(StandardCharsets.UTF_8);
        final int paddedSize = ((headerBytes.length + 7) / 8) * 8;
        ByteBuffer buffer = ByteBuffer.allocate(8 + paddedSize + offset).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(paddedSize).put(headerBytes);
        while (buffer.position() < 8 + paddedSize) {
            buffer.put((byte) ' ');
        }
        byte[] weights = buffer.array();
        HorizonCoefficients coefficients = new HorizonCoefficients(new double[features], new double[2],
            new double[features], new double[features], new double[2][9][features], new boolean[2][9]);
        SeatDistributionPredictor predictor = new SeatDistributionPredictor((route, horizon) -> coefficients,
            new double[] {0, .03, .07, .12, .2, .32, .48, .7, 1});
        double[] vector = new double[features];
        vector[0] = 1;
        SeatForecastResult predicted = predictor.predict(new SeatDistributionInput(vector, "1650", 1, 10, 40, null));
        final double fullChance = predicted.distribution().fullChance();
        final double expectedSeats = predicted.distribution().expectedSeats();
        String vectorText = Arrays.stream(vector)
            .mapToObj(Double::toString)
            .collect(Collectors.joining(","));
        String goldenDigest = sha(String.join("\n", vectorText, "1650", "1", "10", "40",
            Double.toString(fullChance), Double.toString(expectedSeats)).getBytes(StandardCharsets.UTF_8));
        String weightsDigest = sha(weights);
        String referenceDigest = sha("synthetic-reference".getBytes(StandardCharsets.UTF_8));
        String featureContract = "bundle-check-fixture-v1";
        String modelVersion = "seat-distribution-a18-v1";
        String sourceCommit = "0".repeat(40);
        String timeSource = "observation_batch.response_received_at";
        String capacityPolicy = "maximum-seats-ever-observed";
        String statisticsPolicy = "stop-demand-statistics";
        String identity = sha(String.join("\n", featureContract, sourceCommit, modelVersion,
            "synthetic-reference-v1", referenceDigest, weightsDigest,
            String.join(",", SeatForecastDesignMatrix.COLUMN_NAMES), "largestSeatCount=68.0,lowSeatBand=20.0",
            timeSource, capacityPolicy, statisticsPolicy, goldenDigest).getBytes(StandardCharsets.UTF_8));
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("bundleSchemaVersion", "a18-live-bundle-v1");
        manifest.put("modelVersion", modelVersion);
        manifest.put("releaseId", "bundle-check-fixture");
        manifest.put("featureContractVersion", featureContract);
        manifest.put("sourceCommit", sourceCommit);
        manifest.put("routeReference", Map.of("version", "synthetic-reference-v1", "digest", referenceDigest));
        manifest.put("routes", ROUTES);
        manifest.put("horizonStops", IntStream.rangeClosed(1, 12)
                .boxed()
                .toList());
        manifest.put("featureNames", SeatForecastDesignMatrix.COLUMN_NAMES);
        manifest.put("normalizationConstants", Map.of("largestSeatCount", 68.0, "lowSeatBand", 20.0));
        manifest.put("timeSlotSource", timeSource);
        manifest.put("capacityPolicy", capacityPolicy);
        manifest.put("cellStatisticsPolicy", statisticsPolicy);
        manifest.put("tensors", declarations);
        manifest.put("weightsDigest", weightsDigest);
        manifest.put("goldenVectorDigest", goldenDigest);
        manifest.put("identityDigest", identity);
        manifest.put("dataThrough", "2026-09-29T12:00:00Z");
        manifest.put("goldenVector", Map.of("modelRoute", "1650", "stopsAhead", 1,
            "currentSeats", 10, "capacity", 40, "featureVector", Arrays.stream(vector)
                .boxed()
                .toList(),
            "expectedFullChance", fullChance, "expectedSeats", expectedSeats));
        Files.write(directory.resolve("weights.safetensors"), weights);
        Files.writeString(directory.resolve("manifest.json"), CanonicalJson.stringOf(manifest));
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String tensorDataType(String name) {
        if (name.equals("bin_fitted")) {
            return "U8";
        }
        return "F64";
    }

    private static int elementBytes(String dtype) {
        if (dtype.equals("U8")) {
            return 1;
        }
        return 8;
    }
}
