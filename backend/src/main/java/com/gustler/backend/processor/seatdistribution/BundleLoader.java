package com.gustler.backend.processor.seatdistribution;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 계수 묶음을 읽어 검사한다. 하나라도 어긋나면 안 올린다.
 *
 * <p>검사 목록은 {@link BundleCheck} 에, 읽어 쓰는 배열 목록은 {@link BundleTensor} 에 있다.
 * 특징 계약이 확정되면 그 둘만 고친다.
 *
 * <p>배열의 크기를 코드에 안 박는다. 설명 파일이 선언한 노선 수 · 예보 거리 수 · 특징 개수에서
 * 크기를 만들고, 선언과 계수 파일과 그 크기 셋이 모두 같은지 본다. 셋 중 둘만 맞아도 거절한다.
 */
public final class BundleLoader {

    /**
     * 계수 파일이 자기 이름으로 적어 둔 값이다. 글자가 하나라도 다르면 다른 계수 묶음이라
     * 우리가 고쳐 부를 수 없다.
     *
     * <p>가운데 {@code a18} 이 무엇의 줄임인지는 <b>확인되지 않았다.</b> 분석 쪽에서 온 이름이고
     * 배포된 계수 파일과 규약 문서 안에서만 산다. 같이 채점하는 다른 모델 일곱은 전부
     * 서술형 이름이라 일련번호로 보기도 어렵다. 그래서 이 두 줄 밖으로는 안 퍼뜨린다.
     */
    private static final String BUNDLE_SCHEMA_VERSION = "a18-live-bundle-v1";
    private static final String MODEL_VERSION = "seat-distribution-a18-v1";

    /** 노선 순서가 계약이다. 뒤집으면 다른 노선 계수로 예보한다. */
    private static final List<String> ROUTES = List.of("1650", "3330");

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
        checkScope(manifest);
        checkWeightsDigest(manifest, weightsContent);

        SafetensorsFile weights = SafetensorsFile.of(weightsContent);
        checkTensorNames(weights);
        return new CoefficientBundle(manifest, checkedTensorsOf(manifest, weights));
    }

    private static void checkIdentity(
        BundleManifest manifest
    ) {
        BundleCheck.BUNDLE_SCHEMA_VERSION.require(
            BUNDLE_SCHEMA_VERSION.equals(manifest.bundleSchemaVersion()), manifest.bundleSchemaVersion());
        BundleCheck.MODEL_VERSION.require(
            MODEL_VERSION.equals(manifest.modelVersion()), manifest.modelVersion());
        BundleCheck.FEATURE_CONTRACT_VERSION.require(
            !manifest.featureContractVersion().isBlank(), "비어 있다");
        BundleCheck.ROUTE_REFERENCE.require(
            !manifest.routeReferenceVersion().isBlank(), "판 이름이 비어 있다");
        BundleCheck.ROUTE_REFERENCE.require(
            manifest.routeReferenceDigest().length() == 64, manifest.routeReferenceDigest());
    }

    private static void checkScope(
        BundleManifest manifest
    ) {
        BundleCheck.ROUTE_ORDER.require(ROUTES.equals(manifest.routes()), manifest.routes().toString());
        BundleCheck.HORIZON_STOPS.require(
            expectedHorizonStops().equals(manifest.horizonStops()), manifest.horizonStops().toString());
        BundleCheck.FEATURE_NAMES.require(!manifest.featureNames().isEmpty(), "비어 있다");
        BundleCheck.FEATURE_NAMES.require(
            new LinkedHashSet<>(manifest.featureNames()).size() == manifest.featureNames().size(),
            "겹치는 이름이 있다");
    }

    private static List<Integer> expectedHorizonStops() {
        List<Integer> stops = new ArrayList<>();
        for (int ahead = SMALLEST_STOPS_AHEAD; ahead <= LARGEST_STOPS_AHEAD; ahead++) {
            stops.add(ahead);
        }
        return List.copyOf(stops);
    }

    /**
     * 출시 식별자가 무엇으로 만들어졌는지를 요약값 하나로 묶는다.
     *
     * <p>특징 계약 판 · 학습 소스 · 모델 판 · 노선 참조 · 계수 파일 요약값을 이어 붙여 잰다.
     * 이 중 하나만 바뀌어도 요약값이 달라져서, 계수는 그대로인데 뜻만 바뀐 묶음이 같은 출시
     * 식별자로 올라오지 못한다.
     */
    private static void checkWeightsDigest(
        BundleManifest manifest,
        byte[] weightsContent
    ) {
        final String measured = Sha256.of(weightsContent);
        BundleCheck.WEIGHTS_DIGEST.require(measured.equals(manifest.weightsDigest()), measured);
        BundleCheck.RELEASE_IDENTITY_DIGEST.require(
            !manifest.releaseId().isBlank(), "출시 식별자가 비어 있다");
        final String identity = Sha256.of(String.join("\n",
            manifest.featureContractVersion(),
            manifest.sourceCommit(),
            manifest.modelVersion(),
            manifest.routeReferenceVersion(),
            manifest.routeReferenceDigest(),
            manifest.weightsDigest()));
        BundleCheck.RELEASE_IDENTITY_DIGEST.require(
            identity.equals(manifest.identityDigest()), identity);
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
        BundleManifest.TensorDeclaration declaration =
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
            if (wanted.dataType() == TensorDataType.FLOAT64) {
                BundleCheck.COEFFICIENTS_ARE_FINITE.require(
                    Double.isFinite(value), "%s 에 %s 가 있다".formatted(wanted.tensorName(), value));
            } else {
                BundleCheck.FITTED_FLAGS_ARE_ZERO_OR_ONE.require(
                    value == 0.0 || value == 1.0,
                    "%s 에 %s 가 있다".formatted(wanted.tensorName(), value));
            }
        }
    }
}
