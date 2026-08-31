package com.gustler.backend.processor.a18;

import java.util.List;
import java.util.Map;

/**
 * 계수 묶음의 설명 파일을 읽은 값.
 *
 * <p>항목은 v4-1 정본 4.2절이 정한 것이다. 특징 이름과 정규화 상수와 시간대 기준까지 여기
 * 적히는 이유는, 그것이 안 적히면 <b>같은 계수가 다른 뜻으로</b> 쓰일 수 있어서다. 학습 쪽과
 * 서빙 쪽이 같은 값을 만드는지는 이 파일이 유일한 대조 근거다.
 *
 * <p>{@code featureContractVersion} 이 곧 {@code model_deployment.calculation_version} 이다.
 * 그 열의 뜻이 "입력값을 만드는 규칙" 이고 특징 계약이 정확히 그것이라, 둘을 따로 두면 같은 것을
 * 두 이름으로 부르게 된다. 셀 통계도 이 판 이름으로 세대를 고르므로 값이 하나여야 한다.
 *
 * @param tensorDeclarations 계수 파일이 담은 배열의 이름과 크기·자료형. 코드가 아니라 여기가 정한다
 * @param dataThrough 이 계수를 학습한 자료가 어디까지인가. 응답의 trainedThrough 로 나간다
 */
record BundleManifest(
    String bundleSchemaVersion,
    String modelVersion,
    String releaseId,
    String featureContractVersion,
    String sourceCommit,
    String routeReferenceVersion,
    String routeReferenceDigest,
    List<String> routes,
    List<Integer> horizonStops,
    List<String> featureNames,
    Map<String, Double> normalizationConstants,
    String timeSlotSource,
    String capacityPolicy,
    String cellStatisticsPolicy,
    Map<String, TensorDeclaration> tensorDeclarations,
    String weightsDigest,
    String identityDigest,
    String goldenVectorDigest,
    String dataThrough
) {

    record TensorDeclaration(
        TensorDataType dataType,
        int[] shape
    ) {
    }

    int featureCount() {
        return featureNames.size();
    }
}
