package com.gustler.backend.processor.seatdistribution;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import com.gustler.backend.processor.SeatForecastResult;
import java.util.Map;
import com.gustler.backend.processor.SeatForecastDesignMatrix;
import java.util.Random;

/**
 * 검사를 통과하는 더미 계수 묶음을 만들고, 요청하면 한 군데씩 망가뜨린다.
 *
 * <p>진짜 계수 파일이 아직 없다. 그래서 크기와 자료형만 계약대로 맞추고 계수 값은 아무 수나
 * 넣는다. 이 티켓이 재는 것은 값의 정확도가 아니라 <b>검사가 실제로 막는지</b>다.
 *
 * <p>진짜 파일이 오면 이 픽스처를 안 고치고 파일만 갈아 끼운다.
 */
final class DummyBundle {

    static final String BUNDLE_SCHEMA_VERSION = "a18-live-bundle-v1";
    static final String MODEL_VERSION = "seat-distribution-a18-v1";
    static final String FEATURE_CONTRACT_VERSION = "seat-feature-contract-v4-1-draft";
    static final String SOURCE_COMMIT = "e6fe12dc0eb66851b435b2203b6ae8580e141009";
    static final String ROUTE_REFERENCE_VERSION = "gbis-2026-08-19";
    static final String ROUTE_REFERENCE_DIGEST =
        "50568d9a10b567ea0b650cd79ceed39a86947648e303e0d8fd1093840bb54c5e";
    static final List<String> ROUTES = List.of("1650", "3330");
    static final int FEATURE_COUNT = SeatForecastDesignMatrix.COLUMN_NAMES.size();
    static final int HORIZON_COUNT = 12;

    private static final String GOLDEN_ROUTE = "3330";
    private static final int GOLDEN_STOPS_AHEAD = 4;
    private static final int GOLDEN_CURRENT_SEATS = 20;
    private static final int GOLDEN_CAPACITY = 44;

    private final Map<String, Object> manifest = new LinkedHashMap<>();
    private final SafetensorsWriter weights = new SafetensorsWriter();

    private DummyBundle() {
    }

    static DummyBundle valid() {
        DummyBundle bundle = new DummyBundle();
        bundle.fillTensors(FEATURE_COUNT);
        bundle.fillManifest(FEATURE_COUNT);
        return bundle;
    }

    private void fillTensors(
        final int featureCount
    ) {
        Random values = new Random(20260901L);
        for (BundleTensor tensor : BundleTensor.values()) {
            int[] shape = tensor.shapeOf(ROUTES.size(), HORIZON_COUNT, featureCount);
            weights.with(tensor.tensorName(), tensor.dataType(), shape,
                valuesOf(tensor, Tensor.valueCountOf(shape), values));
        }
    }

    private static double[] valuesOf(
        BundleTensor tensor,
        final int count,
        Random values
    ) {
        double[] filled = new double[count];
        for (int index = 0; index < count; index++) {
            filled[index] = tensor.dataType() == TensorDataType.FLOAT64
                ? Math.round(values.nextGaussian() * 4e11) / 1e12
                : (values.nextInt(10) == 0 ? 0 : 1);
        }
        return filled;
    }

    private void fillManifest(
        final int featureCount
    ) {
        manifest.put("bundleSchemaVersion", BUNDLE_SCHEMA_VERSION);
        manifest.put("modelVersion", MODEL_VERSION);
        manifest.put("featureContractVersion", FEATURE_CONTRACT_VERSION);
        manifest.put("sourceCommit", SOURCE_COMMIT);
        manifest.put("routeReferenceVersion", ROUTE_REFERENCE_VERSION);
        manifest.put("routeReferenceDigest", ROUTE_REFERENCE_DIGEST);
        manifest.put("routes", ROUTES);
        manifest.put("horizonStops", horizonStops());
        manifest.put("featureNames", featureNames(featureCount));
        manifest.put("timeSlotSource", "observation_batch.response_received_at");
        manifest.put("capacityPolicy", "maximum-seats-ever-observed");
        manifest.put("cellStatisticsPolicy", "stop-demand-statistics");
        manifest.put("dataThrough", "2026-08-30T14:59:56Z");
    }

    private static List<Integer> horizonStops() {
        List<Integer> stops = new ArrayList<>();
        for (int ahead = 1; ahead <= HORIZON_COUNT; ahead++) {
            stops.add(ahead);
        }
        return stops;
    }

    /**
     * 열 이름은 지어내지 않는다. 설계행렬이 든 것을 그대로 쓴다.
     *
     * <p>열 수를 줄여 보는 픽스처는 뒤에서 잘라 쓴다. 적재가 그것을 거절하는지 보는 자리다.
     */
    private static List<String> featureNames(
        final int featureCount
    ) {
        return List.copyOf(SeatForecastDesignMatrix.COLUMN_NAMES.subList(
            0, Math.min(featureCount, SeatForecastDesignMatrix.COLUMN_NAMES.size())));
    }

    /**
     * 더미의 대조 사례를 만든다.
     *
     * <p>진짜 계수 파일이면 <b>학습 쪽 구현</b>이 이 값을 만들어 싣는다. 더미는 그럴 상대가 없어서
     * 우리 예측기로 만든다. 그래서 이 값은 계산이 맞는지를 못 재고 <b>검사가 실제로 도는지</b>만
     * 잰다. 어긋난 사례를 넣어 거절되는지 보는 테스트가 그 짝이다.
     *
     * <p>적재를 거쳐 만들면 자기를 다시 부르게 되므로 계수 묶음을 곧바로 세워 쓴다.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> castGolden(
        Object golden
    ) {
        return (Map<String, Object>) golden;
    }

    /** 적재 쪽이 재는 것과 같은 순서로 잇는다. 한쪽만 고치면 정상 묶음이 거절된다. */
    @SuppressWarnings("unchecked")
    private static String goldenVectorTextOf(
        Map<String, Object> golden
    ) {
        return String.join("\n",
            ((List<Double>) golden.get("featureVector")).stream()
                .map(String::valueOf).collect(java.util.stream.Collectors.joining(",")),
            String.valueOf(golden.get("modelRoute")),
            String.valueOf(golden.get("stopsAhead")),
            String.valueOf(golden.get("currentSeats")),
            String.valueOf(golden.get("capacity")),
            String.valueOf(golden.get("expectedFullChance")),
            String.valueOf(golden.get("expectedSeats")));
    }

    /** 정상 묶음이 실을 대조 사례. 어긋낸 사례를 만들어 보는 테스트가 여기서 가져간다. */
    Map<String, Object> goldenVectorFrom() {
        return goldenVectorOf(FEATURE_COUNT);
    }

    private Map<String, Object> goldenVectorOf(
        final int featureCount
    ) {
        if (!hasEveryTensor()) {
            return placeholderGoldenVector(featureCount);
        }
        double[] featureVector = new double[featureCount];
        featureVector[0] = 1.0;
        for (int index = 1; index < featureVector.length; index++) {
            featureVector[index] = index % 3 == 0 ? 0.5 : 0.0;
        }
        SeatForecastResult result = new SeatDistributionPredictor(
            coefficientsWithout(featureCount), CoefficientsFixture.RELATIVE_EDGES)
            .predict(new SeatDistributionInput(
                featureVector, GOLDEN_ROUTE, GOLDEN_STOPS_AHEAD,
                GOLDEN_CURRENT_SEATS, GOLDEN_CAPACITY, null));

        Map<String, Object> golden = new LinkedHashMap<>();
        golden.put("featureVector", boxedDoubles(featureVector));
        golden.put("modelRoute", GOLDEN_ROUTE);
        golden.put("stopsAhead", GOLDEN_STOPS_AHEAD);
        golden.put("currentSeats", GOLDEN_CURRENT_SEATS);
        golden.put("capacity", GOLDEN_CAPACITY);
        golden.put("expectedFullChance", result.distribution().fullChance());
        golden.put("expectedSeats", result.distribution().expectedSeats());
        return golden;
    }

    /**
     * 배열이 빠진 픽스처는 대조 사례를 만들 수 없다. 만들려다 여기서 먼저 터지면 적재 검사가
     * 무엇에 걸렸는지 못 보게 되므로, 자리만 채운 사례를 놓고 적재 쪽이 거절하게 둔다.
     */
    private boolean hasEveryTensor() {
        SafetensorsFile file = SafetensorsFile.of(weights.toBytes());
        for (BundleTensor tensor : BundleTensor.values()) {
            if (!file.has(tensor.tensorName())) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Object> placeholderGoldenVector(
        final int featureCount
    ) {
        Map<String, Object> golden = new LinkedHashMap<>();
        golden.put("featureVector", boxedDoubles(new double[featureCount]));
        golden.put("modelRoute", GOLDEN_ROUTE);
        golden.put("stopsAhead", GOLDEN_STOPS_AHEAD);
        golden.put("currentSeats", GOLDEN_CURRENT_SEATS);
        golden.put("capacity", GOLDEN_CAPACITY);
        golden.put("expectedFullChance", 0.0);
        golden.put("expectedSeats", 0.0);
        return golden;
    }

    /** 설명 파일을 안 거치고 계수 묶음만 세운다. 대조 사례를 만드는 자리에서만 쓴다. */
    private CoefficientBundle coefficientsWithout(
        final int featureCount
    ) {
        SafetensorsFile file = SafetensorsFile.of(weights.toBytes());
        Map<BundleTensor, Tensor> tensors = new LinkedHashMap<>();
        for (BundleTensor tensor : BundleTensor.values()) {
            tensors.put(tensor, file.get(tensor.tensorName()));
        }
        return new CoefficientBundle(
            new BundleManifest(
                BUNDLE_SCHEMA_VERSION, MODEL_VERSION, "dummy-release-0001",
                FEATURE_CONTRACT_VERSION, SOURCE_COMMIT, ROUTE_REFERENCE_VERSION,
                ROUTE_REFERENCE_DIGEST, ROUTES, horizonStops(), featureNames(featureCount),
                Map.of(), "", "", "", Map.of(), "", "", "", null, "2026-08-30T14:59:56Z"),
            tensors);
    }

    private static List<Double> boxedDoubles(
        double[] values
    ) {
        List<Double> boxed = new ArrayList<>();
        for (final double value : values) {
            boxed.add(value);
        }
        return boxed;
    }

    DummyBundle put(
        String field,
        Object value
    ) {
        manifest.put(field, value);
        return this;
    }

    DummyBundle remove(
        String field
    ) {
        manifest.remove(field);
        return this;
    }

    DummyBundle withoutTensor(
        BundleTensor tensor
    ) {
        weights.without(tensor.tensorName());
        return this;
    }

    DummyBundle withTensor(
        String name,
        TensorDataType dataType,
        int[] shape,
        double[] values
    ) {
        weights.with(name, dataType, shape, values);
        return this;
    }

    /** 배열 하나의 값 한 칸만 바꾼다. 지금 들어 있는 값을 읽어 그 자리만 고친다. */
    DummyBundle withTensorValue(
        BundleTensor tensor,
        final int index,
        final double value
    ) {
        Tensor current = SafetensorsFile.of(weights.toBytes()).get(tensor.tensorName());
        double[] values = current.values().clone();
        values[index] = value;
        return withTensor(tensor.tensorName(), tensor.dataType(), current.shape(), values);
    }

    /** 특징 개수를 바꿔서 계수 길이와 이름 개수가 같이 움직이는지 본다. */
    static DummyBundle withFeatureCount(
        final int featureCount
    ) {
        DummyBundle bundle = new DummyBundle();
        bundle.fillTensors(featureCount);
        bundle.fillManifest(featureCount);
        return bundle;
    }

    byte[] weightsBytes() {
        return weights.toBytes();
    }

    /** 설명 파일을 쓴다. 배열 선언과 요약값은 실제 계수 파일에서 만들어 넣는다. */
    byte[] manifestBytes() {
        byte[] weightsContent = weights.toBytes();
        Map<String, Object> written = new LinkedHashMap<>(manifest);
        written.put("tensors", declarations());
        written.put("weightsDigest", manifest.containsKey("weightsDigest")
            ? manifest.get("weightsDigest")
            : Sha256.of(weightsContent));
        written.put("releaseId", manifest.containsKey("releaseId")
            ? manifest.get("releaseId")
            : "dummy-release-0001");
        written.put("normalizationConstants", manifest.containsKey("normalizationConstants")
            ? manifest.get("normalizationConstants")
            : Map.of("lowSeatBand", 20.0, "largestSeatCount", 68.0));
        Map<String, Object> golden = manifest.containsKey("goldenVector")
            ? castGolden(manifest.get("goldenVector"))
            : goldenVectorOf(featureNamesOf(written).size());
        written.put("goldenVector", golden);
        written.put("goldenVectorDigest", manifest.containsKey("goldenVectorDigest")
            ? manifest.get("goldenVectorDigest")
            : Sha256.of(goldenVectorTextOf(golden)));
        written.put("identityDigest", manifest.containsKey("identityDigest")
            ? manifest.get("identityDigest")
            : identityDigestOf(written));
        return Json.of(reordered(written)).getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, Object> reordered(
        Map<String, Object> written
    ) {
        Map<String, Object> ordered = new LinkedHashMap<>();
        ordered.put("routeReference", Map.of(
            "version", written.remove("routeReferenceVersion"),
            "digest", written.remove("routeReferenceDigest")));
        ordered.putAll(written);
        return ordered;
    }

    @SuppressWarnings("unchecked")
    private static List<String> featureNamesOf(
        Map<String, Object> written
    ) {
        return (List<String>) written.get("featureNames");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Double> normalizationOf(
        Map<String, Object> written
    ) {
        return (Map<String, Double>) written.get("normalizationConstants");
    }

    /** 적재 쪽이 재는 것과 같은 순서로 잇는다. 한쪽만 고치면 정상 묶음이 거절된다. */
    private static String identityDigestOf(
        Map<String, Object> written
    ) {
        return Sha256.of(String.join("\n",
            String.valueOf(written.get("featureContractVersion")),
            String.valueOf(written.get("sourceCommit")),
            String.valueOf(written.get("modelVersion")),
            String.valueOf(written.get("routeReferenceVersion")),
            String.valueOf(written.get("routeReferenceDigest")),
            String.valueOf(written.get("weightsDigest")),
            String.join(",", featureNamesOf(written)),
            normalizationOf(written).entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(constant -> constant.getKey() + "=" + constant.getValue())
                .collect(java.util.stream.Collectors.joining(",")),
            String.valueOf(written.get("timeSlotSource")),
            String.valueOf(written.get("capacityPolicy")),
            String.valueOf(written.get("cellStatisticsPolicy")),
            String.valueOf(written.get("goldenVectorDigest"))));
    }

    private Map<String, Object> declarations() {
        SafetensorsFile file = SafetensorsFile.of(weights.toBytes());
        Map<String, Object> declared = new LinkedHashMap<>();
        for (String name : file.names()) {
            Tensor tensor = file.get(name);
            declared.put(name, Map.of(
                "dtype", tensor.dataType().tensorName(),
                "shape", boxed(tensor.shape())));
        }
        return declared;
    }

    private static List<Integer> boxed(
        int[] shape
    ) {
        List<Integer> axes = new ArrayList<>();
        for (final int length : shape) {
            axes.add(length);
        }
        return axes;
    }

    BundleFiles writeTo(
        Path directory
    ) {
        try {
            Files.write(directory.resolve(BundleFiles.MANIFEST_NAME), manifestBytes());
            Files.write(directory.resolve(BundleFiles.WEIGHTS_NAME), weightsBytes());
            return BundleFiles.under(directory);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
