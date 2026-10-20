package com.gustler.backend.forecasting.infrastructure.bundle;

import com.gustler.backend.forecasting.domain.model.ForecastFeatureContract;
import com.gustler.backend.forecasting.domain.model.Sha256;
import com.gustler.backend.forecasting.infrastructure.bundle.BundleManifest.GoldenVector;
import com.gustler.backend.forecasting.infrastructure.bundle.BundleManifest.TensorDeclaration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 계수 묶음을 읽고 검사하며 하나라도 어긋나면 적재하지 않는다.
 *
 * <p>검사 목록은 {@link BundleCheck} 에, 읽어 사용하는 배열 목록은 {@link BundleTensor} 에 있다.
 * 특징 계약이 확정되면 두 목록만 수정한다.
 *
 * <p>배열 크기를 코드에 고정하지 않는다. 설명 파일이 선언한 노선 수 · 예보 거리 수 · 특징 개수로
 * 크기를 계산하고, 선언 · 계수 파일 · 계산한 크기가 모두 같은지 확인한다. 셋 중 둘만 맞아도 거절한다.
 */
public final class BundleLoader {
    /**
     * 계수 파일에 명시된 이름으로, 글자가 하나라도 다르면 다른 계수 묶음이므로 임의로 바꾸지 않는다.
     *
     * <p>가운데 {@code a18} 이 무엇의 줄임인지는 확인되지 않았다. 분석 쪽에서 정한 이름이며
     * 배포된 계수 파일과 규약 문서에서만 사용한다. 함께 채점하는 다른 모델 일곱은 전부
     * 서술형 이름이므로 일련번호로 보기도 어렵다. 따라서 이 두 줄 밖에서는 사용하지 않는다.
     */
    private static final String BUNDLE_SCHEMA_VERSION = "a18-live-bundle-v1";
    static final String CONDITIONAL_SCHEMA_VERSION = "a18-live-bundle-v2";
    private static final String MODEL_VERSION = "seat-distribution-a18-v1";

    /** 노선 순서가 계약이다. 뒤집으면 다른 노선 계수로 예보한다. */
    private static final List<List<String>> ROUTE_ORDERS = List.of(
        List.of("1650", "3330"),
        List.of("1650", "3330", "9007", "9300", "6011", "3000", "5600", "3500"),
        List.of("1650", "3330", "9007", "9300", "6011", "3000", "5600", "3500",
            "1151", "9000", "1500-2", "8100", "1150", "G8110", "6801", "6003", "3100", "1101", "7007-1", "4000"));

    private static final int SMALLEST_STOPS_AHEAD = 1;
    private static final int LARGEST_STOPS_AHEAD = 12;

    private BundleLoader() {
    }

    static CoefficientBundle load(
        BundleFiles files
    ) {
        byte[] manifestContent = files.readManifest();
        byte[] weightsContent = files.readWeights();

        BundleManifest manifest = BundleManifestReader.read(manifestContent);
        checkIdentity(manifest);
        checkDeploymentMetadata(manifest);
        checkScope(manifest);
        checkConditionalContract(manifest);
        checkWeightsDigest(manifest, weightsContent);
        checkGoldenVectorDigest(manifest);

        SafetensorsFile weights = SafetensorsFile.of(weightsContent);
        checkTensorNames(weights);
        return new CoefficientBundle(manifest, checkedTensorsOf(manifest, weights));
    }

    private static void checkIdentity(
        BundleManifest manifest
    ) {
        BundleCheck.BUNDLE_SCHEMA_VERSION.require(
            (BUNDLE_SCHEMA_VERSION.equals(manifest.bundleSchemaVersion())
                || CONDITIONAL_SCHEMA_VERSION.equals(manifest.bundleSchemaVersion())), manifest.bundleSchemaVersion());
        BundleCheck.MODEL_VERSION.require(
            MODEL_VERSION.equals(manifest.modelVersion()), manifest.modelVersion());
        BundleCheck.FEATURE_CONTRACT_VERSION.require(
            !manifest.featureContractVersion().isBlank(), "비어 있다");
        BundleCheck.ROUTE_REFERENCE.require(
            !manifest.routeReferenceVersion().isBlank(), "판 이름이 비어 있다");
        BundleCheck.ROUTE_REFERENCE.require(
            manifest.routeReferenceDigest().length() == 64, manifest.routeReferenceDigest());
    }

    /** DB 저장이나 메모리 적재 전에 배포 메타데이터를 확인한다. */
    private static void checkDeploymentMetadata(
        BundleManifest manifest
    ) {
        checkIdentifierLength("releaseId", manifest.releaseId(), 80);
        checkIdentifierLength("featureContractVersion", manifest.featureContractVersion(), 40);
        try {
            Instant.parse(manifest.dataThrough());
        } catch (DateTimeParseException error) {
            throw BundleCheck.DATA_THROUGH.reject("dataThrough 는 Instant 형식이어야 한다");
        }
    }

    private static void checkIdentifierLength(
        String field,
        String value,
        final int maximum
    ) {
        final int characters = value.codePointCount(0, value.length());
        BundleCheck.DEPLOYMENT_IDENTIFIER_LENGTH.require(
            characters <= maximum,
                "%s: %d자, 최대 %d자".formatted(field, characters, maximum));
    }

    private static void checkScope(
        BundleManifest manifest
    ) {
        BundleCheck.ROUTE_ORDER.require(ROUTE_ORDERS.contains(manifest.routes()), manifest.routes().toString());
        BundleCheck.HORIZON_STOPS.require(
            expectedHorizonStops().equals(manifest.horizonStops()), manifest.horizonStops().toString());
        BundleCheck.FEATURE_NAMES.require(
            featuresOf(manifest).featureNames().equals(manifest.featureNames()),
            "우리 열 %d개, 계수 파일 %d개. 처음 어긋나는 자리는 %s"
                .formatted(
                    featuresOf(manifest).featureNames().size(),
                    manifest.featureNames().size(),
                    firstDifferenceOf(featuresOf(manifest).featureNames(), manifest.featureNames())));
    }

    static ForecastFeatureContract featuresOf(BundleManifest manifest) {
        if (!CONDITIONAL_SCHEMA_VERSION.equals(manifest.bundleSchemaVersion())) {
            return ForecastFeatureContract.LEGACY;
        }
        if (ForecastFeatureContract.STATISTICS_VERSION.equals(manifest.featureContractVersion())) {
            return ForecastFeatureContract.STOP_DIRECTION_TIME_STATISTICS;
        }
        return ForecastFeatureContract.STOP_DIRECTION_TIME;
    }

    private static void checkConditionalContract(BundleManifest manifest) {
        final boolean conditional = featuresOf(manifest) != ForecastFeatureContract.LEGACY;
        final boolean statistics = ForecastFeatureContract.STATISTICS_VERSION.equals(manifest.featureContractVersion());
        BundleCheck.FEATURE_CONTRACT_VERSION.require(
            conditional == (statistics
                        || ForecastFeatureContract.CONDITIONAL_VERSION.equals(manifest.featureContractVersion())),
                "새 입력 계약은 v2 번들에서만 지원한다: " + manifest.featureContractVersion());
        if (conditional) {
            BundleCheck.FEATURE_CONTRACT_VERSION.require(
                manifest.normalizationConstants().equals(Map.of("largestSeatCount", 68.0, "lowSeatBandWidth", 20.0))
                    && manifest.timeSlotSource()
                    .equals("observation_batch.response_received_at;Asia/Seoul;new_time_slot=0")
                    && manifest.capacityPolicy().equals("maximum-seats-ever-observed")
                    && manifest.cellStatisticsPolicy().equals(statisticsPolicy(statistics)),
            "위치·방향·시간 계약의 정규화·시간·정원·통계 규칙이 다르다");
        }
    }

    private static List<Integer> expectedHorizonStops() {
        List<Integer> stops = IntStream.rangeClosed(SMALLEST_STOPS_AHEAD, LARGEST_STOPS_AHEAD)
            .boxed()
            .collect(Collectors.toCollection(ArrayList::new));
        return List.copyOf(stops);
    }

    /**
     * 출시 식별자를 구성하는 정보를 하나의 요약값으로 묶는다.
     *
     * <p>계수 파일 요약값만으로는 충분하지 않다. 계수가 한 글자도 바뀌지 않아도 입력 생성 규칙이
     * 바뀌면 계수의 의미가 달라진다. 따라서 열 이름과 순서 · 정규화 상수 · 시간대 기준 ·
     * 정원 정책 · 셀 통계 정책 · 대조 사례 요약값을 함께 포함한다.
     *
     * <p>어느 하나라도 바뀌면 요약값이 달라지므로 의미가 달라진 묶음이 같은 출시 식별자로 적재되지
     * 않는다.
     */
    private static void checkWeightsDigest(
        BundleManifest manifest,
        byte[] weightsContent
    ) {
        String measured = Sha256.of(weightsContent);
        BundleCheck.WEIGHTS_DIGEST.require(measured.equals(manifest.weightsDigest()), measured);
        BundleCheck.RELEASE_IDENTITY_DIGEST.require(
            !manifest.releaseId().isBlank(), "출시 식별자가 비어 있다");
        String identity = Sha256.of(String.join("\n",
            manifest.featureContractVersion(),
            manifest.sourceCommit(),
            manifest.modelVersion(),
            manifest.routeReferenceVersion(),
            manifest.routeReferenceDigest(),
            manifest.weightsDigest(),
            String.join(",", manifest.featureNames()),
            normalizationText(manifest),
            manifest.timeSlotSource(),
            manifest.capacityPolicy(),
            manifest.cellStatisticsPolicy(),
            manifest.goldenVectorDigest()));
        BundleCheck.RELEASE_IDENTITY_DIGEST.require(
            identity.equals(manifest.identityDigest()), identity);
    }

    /** 정규화 상수를 이름순으로 나열한다. 원래 순서가 달라도 같은 요약값이 나와야 한다. */
    private static String normalizationText(
        BundleManifest manifest
    ) {
        return manifest.normalizationConstants()
            .entrySet()
            .stream()
            .sorted(Entry.comparingByKey())
            .map(constant -> constant.getKey() + "=" + constant.getValue())
            .collect(Collectors.joining(","));
    }

    private static String firstDifferenceOf(
        List<String> expected, List<String> featureNames
    ) {
        for (int index = 0; index < Math.min(expected.size(), featureNames.size()); index++) {
            if (!expected.get(index).equals(featureNames.get(index))) {
                return "%d번 열. 우리 %s, 계수 파일 %s"
                    .formatted(index + 1, expected.get(index), featureNames.get(index));
            }
        }
        return "개수만 다르다";
    }

    /**
     * 대조 사례의 내용으로 계산한 요약값이 명시된 요약값과 같은지 확인한다.
     *
     * <p>이 검사가 없으면 요약값을 임의로 적고 사례 내용을 바꿔도 식별 정보는 그대로다.
     * 식별 정보 요약값에는 명시된 사례 요약값만 포함되므로 사례 내용도 별도로 검증해야 한다.
     *
     * <p>사례를 이어 붙이는 순서가 계약이다. 생성하는 쪽과 검증하는 쪽의 순서가 다르면 정상 묶음이 거절된다.
     */
    private static void checkGoldenVectorDigest(
        BundleManifest manifest
    ) {
        String measured = Sha256.of(goldenVectorText(manifest.goldenVector()));
        BundleCheck.GOLDEN_VECTOR.require(
            measured.equals(manifest.goldenVectorDigest()),
            "적힌 요약값 %s, 사례 내용에서 잰 값 %s"
                .formatted(manifest.goldenVectorDigest(), measured));
    }

    static String goldenVectorText(
        GoldenVector golden
    ) {
        return String.join("\n",
            golden.featureVector()
                .stream()
                .map(String::valueOf)
                .collect(Collectors.joining(",")),
            golden.modelRoute(),
            String.valueOf(golden.stopsAhead()),
            String.valueOf(golden.currentSeats()),
            String.valueOf(golden.capacity()),
            String.valueOf(golden.expectedFullChance()),
            String.valueOf(golden.expectedSeats()));
    }

    private static void checkTensorNames(
        SafetensorsFile weights
    ) {
        for (String name : weights.names()) {
            BundleCheck.WEIGHTS_HAS_NO_UNKNOWN_TENSOR.require(BundleTensor.known(name), name);
        }
    }

    private static Map<BundleTensor, Tensor> checkedTensorsOf(
        BundleManifest manifest,
        SafetensorsFile weights
    ) {
        Set<String> declared = manifest.tensorDeclarations().keySet();
        Map<BundleTensor, Tensor> tensors = new EnumMap<>(BundleTensor.class);
        for (BundleTensor wanted : BundleTensor.values()) {
            BundleCheck.REQUIRED_TENSORS_PRESENT.require(
                declared.contains(wanted.tensorName()), "설명 파일에 없다: " + wanted.tensorName());
            BundleCheck.REQUIRED_TENSORS_PRESENT.require(
                weights.has(wanted.tensorName()), "계수 파일에 없다: " + wanted.tensorName());
            tensors.put(wanted, checked(wanted, manifest, weights.get(wanted.tensorName())));
        }
        return tensors;
    }

    private static Tensor checked(
        BundleTensor wanted,
        BundleManifest manifest,
        Tensor tensor
    ) {
        int[] expected = wanted.shapeOf(
            manifest.routes().size(), manifest.horizonStops().size(), manifest.featureCount());
        TensorDeclaration declaration =
            manifest.tensorDeclarations().get(wanted.tensorName());

        BundleCheck.TENSOR_SHAPE_AND_DATA_TYPE.require(
            Arrays.equals(expected, declaration.shape()),
                "%s 선언 %s, 계약 %s".formatted(
                    wanted.tensorName(), Arrays.toString(declaration.shape()), Arrays.toString(expected)));
        BundleCheck.TENSOR_SHAPE_AND_DATA_TYPE.require(
            Arrays.equals(expected, tensor.shape()),
                "%s 파일 %s, 계약 %s".formatted(
                    wanted.tensorName(), Arrays.toString(tensor.shape()), Arrays.toString(expected)));
        BundleCheck.TENSOR_SHAPE_AND_DATA_TYPE.require(
            wanted.dataType() == declaration.dataType() && wanted.dataType() == tensor.dataType(),
                "%s 자료형이 %s 가 아니다".formatted(wanted.tensorName(), wanted.dataType().tensorName()));

        checkValues(wanted, tensor);
        return tensor;
    }

    private static void checkValues(
        BundleTensor wanted,
        Tensor tensor
    ) {
        for (final double value : tensor.values()) {
            checkValue(wanted, value);
        }
    }

    private static String statisticsPolicy(final boolean statistics) {
        if (statistics) {
            return ForecastFeatureContract.STATISTICS_POLICY;
        }
        return "statistics-inputs-zero";
    }

    private static void checkValue(BundleTensor wanted, final double value) {
        if (wanted.dataType() == TensorDataType.FLOAT64) {
            BundleCheck.COEFFICIENTS_ARE_FINITE.require(
                Double.isFinite(value), "%s 에 %s 가 있다".formatted(wanted.tensorName(), value));
            return;
        }
        BundleCheck.FITTED_FLAGS_ARE_ZERO_OR_ONE.require(
            value == 0.0 || value == 1.0,
                "%s 에 %s 가 있다".formatted(wanted.tensorName(), value));
    }
}
