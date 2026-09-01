package com.gustler.backend.processor.seatdistribution;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    static final int FEATURE_COUNT = 31;
    static final int HORIZON_COUNT = 12;

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
        manifest.put("goldenVectorDigest", "0".repeat(64));
        manifest.put("dataThrough", "2026-08-30T14:59:56Z");
    }

    private static List<Integer> horizonStops() {
        List<Integer> stops = new ArrayList<>();
        for (int ahead = 1; ahead <= HORIZON_COUNT; ahead++) {
            stops.add(ahead);
        }
        return stops;
    }

    private static List<String> featureNames(
        final int featureCount
    ) {
        List<String> names = new ArrayList<>();
        for (int index = 0; index < featureCount; index++) {
            names.add("feature_" + index);
        }
        return names;
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

    private static String identityDigestOf(
        Map<String, Object> written
    ) {
        return Sha256.of(String.join("\n",
            String.valueOf(written.get("featureContractVersion")),
            String.valueOf(written.get("sourceCommit")),
            String.valueOf(written.get("modelVersion")),
            String.valueOf(written.get("routeReferenceVersion")),
            String.valueOf(written.get("routeReferenceDigest")),
            String.valueOf(written.get("weightsDigest"))));
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
