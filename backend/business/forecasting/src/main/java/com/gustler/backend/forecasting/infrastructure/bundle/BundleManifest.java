package com.gustler.backend.forecasting.infrastructure.bundle;

import com.gustler.backend.forecasting.infrastructure.bundle.BundleManifest.GoldenVector;
import com.gustler.backend.forecasting.infrastructure.bundle.BundleManifest.TensorDeclaration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 계수 묶음의 설명 파일을 읽은 값.
 *
 * <p>항목은 v4-1 정본 4.2절을 따른다. 특징 이름 · 정규화 상수 · 시간대 기준이 없으면
 * 같은 계수가 다른 의미로 사용될 수 있다. 학습 쪽과 서빙 쪽이 같은 값을 생성하는지는
 * 이 파일이 유일한 대조 근거다.
 *
 * <p>{@code featureContractVersion} 이 곧 {@code model_deployment.calculation_version} 이다.
 * 두 값 모두 입력값 생성 규칙을 나타내므로 따로 두면 같은 값을 두 이름으로 관리하게 된다.
 * 셀 통계도 이 버전 이름으로 세대를 선택하므로 값이 하나여야 한다.
 *
 * @param tensorDeclarations 계수 파일에 포함된 배열의 이름과 크기·자료형. 코드가 아니라 설명 파일에서 정한다
 * @param dataThrough 계수 학습에 사용한 자료의 종료 시점. 응답의 trainedThrough 로 반환한다
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
    GoldenVector goldenVector,
    String dataThrough
) {
    /** 원본 참조와 분리하고 기존 null·원소·순서를 보존한다. */
    BundleManifest {
        if (routes != null) {
            routes = Collections.unmodifiableList(new ArrayList<>(routes));
        }
        if (horizonStops != null) {
            horizonStops = Collections.unmodifiableList(new ArrayList<>(horizonStops));
        }
        if (featureNames != null) {
            featureNames = Collections.unmodifiableList(new ArrayList<>(featureNames));
        }
        if (normalizationConstants != null) {
            normalizationConstants = Collections.unmodifiableMap(new LinkedHashMap<>(normalizationConstants));
        }
        if (tensorDeclarations != null) {
            tensorDeclarations = Collections.unmodifiableMap(new LinkedHashMap<>(tensorDeclarations));
        }
    }

    /**
     * 계수 파일에 포함된 대조 사례 하나.
     *
     * <p>학습 쪽 구현에 이 입력을 넣어 얻은 결과다. 같은 입력으로 같은 값을 계산하는지 대조해
     * 두 구현의 계산이 같은지 확인한다. 이 검사가 없으면 크기와 자료형만 맞는 계수 파일로
     * 다른 계산을 수행해도 발견할 수 없다.
     */
    record GoldenVector(
        List<Double> featureVector,
        String modelRoute,
        int stopsAhead,
        int currentSeats,
        int capacity,
        double expectedFullChance,
        double expectedSeats
    ) {
        /** 원본 참조와 분리하고 기존 null·원소·순서를 보존한다. */
        GoldenVector {
            if (featureVector != null) {
                featureVector = Collections.unmodifiableList(new ArrayList<>(featureVector));
            }
        }
    }

    /**
     * 텐서의 자료형과 축 크기 값이다. 크기는 입력·접근 시 복사하고 동등성·해시·문자열은 배열 내용으로 정한다.
     * null과 유효하지 않은 크기도 이 전달값에서는 보존하며 파일의 유효성 검사는 로더가 맡는다.
     */
    record TensorDeclaration(
        TensorDataType dataType,
        int[] shape
    ) {
        /** 원본 참조와 분리하고 기존 null·원소·순서를 보존한다. */
        TensorDeclaration {
            if (shape != null) {
                shape = shape.clone();
            }
        }

        public int[] shape() {
            if (shape == null) {
                return null;
            }
            return shape.clone();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof TensorDeclaration declaration)) {
                return false;
            }
            return dataType == declaration.dataType && Arrays.equals(shape, declaration.shape);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hashCode(dataType) + Arrays.hashCode(shape);
        }

        @Override
        public String toString() {
            return "TensorDeclaration[dataType=" + dataType + ", shape=" + Arrays.toString(shape) + "]";
        }
    }

    int featureCount() {
        return featureNames.size();
    }
}
