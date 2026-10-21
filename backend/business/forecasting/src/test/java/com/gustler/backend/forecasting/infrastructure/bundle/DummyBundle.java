package com.gustler.backend.forecasting.infrastructure.bundle;

import com.gustler.backend.forecasting.domain.model.CoefficientsFixture;
import com.gustler.backend.forecasting.domain.model.ForecastFeatureContract;
import com.gustler.backend.forecasting.domain.model.SeatDistributionInput;
import com.gustler.backend.forecasting.domain.model.SeatDistributionPredictor;
import com.gustler.backend.forecasting.domain.model.SeatForecastDesignMatrix;
import com.gustler.backend.forecasting.domain.model.SeatForecastResult;
import com.gustler.backend.forecasting.domain.model.Sha256;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 검사를 통과하는 더미 계수 묶음을 만들고 요청에 따라 한 항목씩 잘못된 값으로 바꾼다.
 *
 * <p>실제 계수 파일이 아직 없어 크기와 자료형만 계약에 맞추고 계수 값은 임의로 정한다.
 * 이 티켓에서는 값의 정확도가 아니라 검사가 잘못된 묶음을 실제로 거절하는지 확인한다.
 *
 * <p>실제 파일이 오면 픽스처를 수정하지 않고 파일만 교체한다.
 */
public final class DummyBundle {
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
    private final List<String> routes;
    private byte[] routeReference;

    private DummyBundle(
        List<String> routes
    ) {
        this.routes = routes;
    }

    public static DummyBundle valid() {
        return withRoutes(ROUTES);
    }

    static DummyBundle withRoutes(
        List<String> routes
    ) {
        DummyBundle bundle = new DummyBundle(routes);
        bundle.fillTensors(FEATURE_COUNT);
        bundle.fillManifest(FEATURE_COUNT);
        return bundle;
    }

    public static DummyBundle conditional() {
        DummyBundle bundle = new DummyBundle(ROUTES);
        bundle.fillTensors(50);
        bundle.fillManifest(50);
        bundle.put("bundleSchemaVersion", "a18-live-bundle-v2");
        bundle.put("featureContractVersion", ForecastFeatureContract.CONDITIONAL_VERSION);
        bundle.put("featureNames", ForecastFeatureContract.STOP_DIRECTION_TIME.featureNames());
        bundle.put("normalizationConstants", Map.of("largestSeatCount", 68.0, "lowSeatBandWidth", 20.0));
        bundle.put("timeSlotSource", "observation_batch.response_received_at;Asia/Seoul;new_time_slot=0");
        bundle.put("cellStatisticsPolicy", "statistics-inputs-zero");
        List<Map<String, Object>> references = new ArrayList<>();
        for (String route : ROUTES) {
            references.add(Map.of("modelRoute", route,
                "sourceRouteId", sourceRouteIdOf(route),
                "stops", List.of(
                    Map.of("order", 1, "id", "same", "boardingAllowed", true, "direction", "UP"),
                    Map.of("order", 2, "id", "same", "boardingAllowed", true, "direction", "DOWN"),
                    Map.of("order", 3, "id", "last", "boardingAllowed", true, "direction", "DOWN"))));
        }
        bundle.routeReference = Json.of(Map.of("version", ROUTE_REFERENCE_VERSION, "routes", references))
            .getBytes(StandardCharsets.UTF_8);
        bundle.put("routeReferenceDigest", Sha256.of(bundle.routeReference));
        return bundle;
    }

    /** 특징 개수를 바꿔서 계수 길이와 이름 개수가 같이 움직이는지 본다. */
    static DummyBundle withFeatureCount(
        final int featureCount
    ) {
        DummyBundle bundle = new DummyBundle(ROUTES);
        bundle.fillTensors(featureCount);
        bundle.fillManifest(featureCount);
        return bundle;
    }

    public LoadedBundle loadAt(Path directory) {
        return LoadedBundle.from(writeTo(directory));
    }

    private void fillTensors(final int featureCount) {
        Random values = new Random(20260901L);
        for (BundleTensor tensor : BundleTensor.values()) {
            int[] shape = tensor.shapeOf(routes.size(), HORIZON_COUNT, featureCount);
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
            filled[index] = randomValue(tensor.dataType(), values);
        }
        return filled;
    }

    private void fillManifest(final int featureCount) {
        manifest.put("bundleSchemaVersion", BUNDLE_SCHEMA_VERSION);
        manifest.put("modelVersion", MODEL_VERSION);
        manifest.put("featureContractVersion", FEATURE_CONTRACT_VERSION);
        manifest.put("sourceCommit", SOURCE_COMMIT);
        manifest.put("routeReferenceVersion", ROUTE_REFERENCE_VERSION);
        manifest.put("routeReferenceDigest", ROUTE_REFERENCE_DIGEST);
        manifest.put("routes", routes);
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
     * 설계행렬에 정의된 열 이름을 그대로 사용한다.
     *
     * <p>열 수를 줄이는 픽스처는 뒤쪽 열 이름을 잘라서 적재 검사가 이를 거절하는지 확인한다.
     */
    private static List<String> featureNames(
        final int featureCount
    ) {
        return List.copyOf(SeatForecastDesignMatrix.COLUMN_NAMES.subList(
            0, Math.min(featureCount, SeatForecastDesignMatrix.COLUMN_NAMES.size())));
    }

    /**
     * 더미의 대조 사례를 생성한다.
     *
     * <p>실제 계수 파일의 대조 사례는 학습 쪽 구현에서 생성하지만 더미는 예측기로 생성한다.
     * 따라서 계산의 정확성은 검증할 수 없으며 검사가 실제 실행되는지만 확인한다.
     * 잘못된 대조 사례를 입력해 거절되는지 확인하는 테스트와 함께 사용한다.
     *
     * <p>적재를 거쳐 생성하면 재귀 호출이 발생하므로 계수 묶음을 직접 구성한다.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> castGolden(
        Object golden
    ) {
        return (Map<String, Object>) golden;
    }

    /** 적재 검증과 같은 순서로 이어 붙인다. 한쪽만 바꾸면 정상 묶음도 거절된다. */
    @SuppressWarnings("unchecked")
    private static String goldenVectorTextOf(
        Map<String, Object> golden
    ) {
        return String.join("\n",
            ((List<Double>) golden.get("featureVector")).stream()
                .map(String::valueOf)
                .collect(Collectors.joining(",")),
            String.valueOf(golden.get("modelRoute")),
            String.valueOf(golden.get("stopsAhead")),
            String.valueOf(golden.get("currentSeats")),
            String.valueOf(golden.get("capacity")),
            String.valueOf(golden.get("expectedFullChance")),
            String.valueOf(golden.get("expectedSeats")));
    }

    /** 정상 묶음에 포함할 대조 사례. 잘못된 사례를 만드는 테스트에서도 이 값을 사용한다. */
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
            featureVector[index] = fixtureCoefficient(index);
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
     * 배열이 빠진 픽스처는 대조 사례를 만들 수 없다. 여기서 먼저 실패하면 적재 검사가
     * 어느 조건에서 거절하는지 확인할 수 없으므로 임시 사례를 채우고 적재 검사에서 거절하도록 한다.
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

    /** 설명 파일을 거치지 않고 계수 묶음을 직접 구성한다. 대조 사례 생성에만 사용한다. */
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
                ROUTE_REFERENCE_DIGEST, routes, horizonStops(), featureNames(featureCount),
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

    byte[] weightsBytes() {
        return weights.toBytes();
    }

    /** 설명 파일을 쓴다. 배열 선언과 요약값은 실제 계수 파일에서 만들어 넣는다. */
    byte[] manifestBytes() {
        byte[] weightsContent = weights.toBytes();
        Map<String, Object> written = new LinkedHashMap<>(manifest);
        written.put("tensors", declarations());
        written.put("weightsDigest", manifestValueOrCompute("weightsDigest", () -> Sha256.of(weightsContent)));
        written.put("releaseId", manifestValueOrCompute("releaseId", () -> "dummy-release-0001"));
        written.put("normalizationConstants",
            manifestValueOrCompute("normalizationConstants",
                () -> Map.of("lowSeatBand", 20.0, "largestSeatCount", 68.0)));
        Map<String, Object> golden = goldenVectorFor(written);
        written.put("goldenVector", golden);
        written.put("goldenVectorDigest",
            manifestValueOrCompute("goldenVectorDigest", () -> Sha256.of(goldenVectorTextOf(golden))));
        written.put("identityDigest", manifestValueOrCompute("identityDigest", () -> identityDigestOf(written)));
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

    /** 적재 검증과 같은 순서로 이어 붙인다. 한쪽만 바꾸면 정상 묶음도 거절된다. */
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
            normalizationOf(written).entrySet()
                .stream()
                .sorted(Entry.comparingByKey())
                .map(constant -> constant.getKey() + "=" + constant.getValue())
                .collect(Collectors.joining(",")),
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
            if (routeReference != null) {
                Files.write(directory.resolve("route-reference.json"), routeReference);
            }
            Files.write(directory.resolve(BundleFiles.MANIFEST_NAME), manifestBytes());
            Files.write(directory.resolve(BundleFiles.WEIGHTS_NAME), weightsBytes());
            return BundleFiles.under(directory);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static String sourceRouteIdOf(String route) {
        if (route.equals("1650")) {
            return "234000050";
        }
        return "204000057";
    }

    /** 자료형별 RNG 호출을 선택된 경로에서만 실행해 더미 계수의 난수 소비 순서를 유지한다. */
    private static double randomValue(TensorDataType dataType, Random values) {
        if (dataType == TensorDataType.FLOAT64) {
            return Math.round(values.nextGaussian() * 4e11) / 1e12;
        }
        if (values.nextInt(10) == 0) {
            return 0;
        }
        return 1;
    }

    private static double fixtureCoefficient(final int index) {
        if (index % 3 == 0) {
            return 0.5;
        }
        return 0.0;
    }

    /** 명시적인 null도 그대로 보존하며, 키가 없을 때에만 기본값을 계산한다. 입력 manifest는 바꾸지 않는다. */
    private Object manifestValueOrCompute(String key, Supplier<Object> fallback) {
        if (manifest.containsKey(key)) {
            return manifest.get(key);
        }
        return fallback.get();
    }

    private Map<String, Object> goldenVectorFor(Map<String, Object> written) {
        if (manifest.containsKey("goldenVector")) {
            return castGolden(manifest.get("goldenVector"));
        }
        return goldenVectorOf(featureNamesOf(written).size());
    }
}
