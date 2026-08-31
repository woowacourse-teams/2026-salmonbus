package com.gustler.backend.processor.a18;

/**
 * 좌석 분포 계산이 실제로 쓰는 배열.
 *
 * <p>계수 파일에 무엇이 몇 개 들어 있는지는 설명 파일이 선언한다. 이 목록은 <b>그중 우리가
 * 읽어 쓰는 것</b>이다. 다섯 중 하나라도 없으면 분포를 못 만들어서 거절한다.
 *
 * <p>크기를 상수로 안 박는다. 노선 수 · 예보 거리 수 · 특징 개수는 설명 파일이 정하고
 * 여기서는 축이 어떤 순서로 놓이는지만 안다. 특징 개수가 31 로 고정되지 않는다는 것이
 * v4-1 정본 3.3절이 상수들을 미결로 둔 결과다.
 *
 * <p>여기 없는 배열이 계수 파일에 있으면 적재를 거절한다. 검사할 줄 모르는 값을 지나치는 것보다
 * 멈추는 편이 낫다. 데모 번들이 들고 있던 동결 셀 통계와 도착 lead 가 그래서 안 들어온다.
 * v4-1 정본 6절이 그 둘을 최신 구현보다 우선해서 가져오면 안 되는 값으로 못박았다.
 */
enum BundleTensor {

    FULL_CHANCE("hurdle_coefficients", TensorDataType.FLOAT64, Layout.BY_FEATURE),
    ANCHOR("anchor_coefficients", TensorDataType.FLOAT64, Layout.BY_ANCHOR_TERM),
    RESIDUAL_SIGN("sign_coefficients", TensorDataType.FLOAT64, Layout.BY_DIRECTION_AND_FEATURE),
    RESIDUAL_BIN("bin_coefficients", TensorDataType.FLOAT64, Layout.BY_DIRECTION_AND_BIN_AND_FEATURE),
    RESIDUAL_BIN_FITTED("bin_fitted", TensorDataType.UINT8, Layout.BY_DIRECTION_AND_BIN),
    ;

    /** 중심 좌석 계수는 절편과 기울기 둘이다. */
    private static final int ANCHOR_TERM_COUNT = 2;

    private enum Layout {
        BY_FEATURE,
        BY_ANCHOR_TERM,
        BY_DIRECTION_AND_FEATURE,
        BY_DIRECTION_AND_BIN,
        BY_DIRECTION_AND_BIN_AND_FEATURE,
        ;
    }

    private final String tensorName;
    private final TensorDataType dataType;
    private final Layout layout;

    BundleTensor(
        String tensorName,
        TensorDataType dataType,
        Layout layout
    ) {
        this.tensorName = tensorName;
        this.dataType = dataType;
        this.layout = layout;
    }

    String tensorName() {
        return tensorName;
    }

    TensorDataType dataType() {
        return dataType;
    }

    /** 앞의 두 축은 언제나 노선과 예보 거리다. 뒤에 붙는 축이 배열마다 다르다. */
    int[] shapeOf(
        final int routeCount,
        final int horizonCount,
        final int featureCount
    ) {
        final int directions = ResidualDirection.values().length;
        return switch (layout) {
            case BY_FEATURE -> new int[] {routeCount, horizonCount, featureCount};
            case BY_ANCHOR_TERM -> new int[] {routeCount, horizonCount, ANCHOR_TERM_COUNT};
            case BY_DIRECTION_AND_FEATURE ->
                new int[] {routeCount, horizonCount, directions, featureCount};
            case BY_DIRECTION_AND_BIN ->
                new int[] {routeCount, horizonCount, directions, HorizonCoefficients.BIN_COUNT};
            case BY_DIRECTION_AND_BIN_AND_FEATURE -> new int[] {
                routeCount, horizonCount, directions, HorizonCoefficients.BIN_COUNT, featureCount};
        };
    }

    static boolean known(
        String tensorName
    ) {
        for (BundleTensor tensor : values()) {
            if (tensor.tensorName.equals(tensorName)) {
                return true;
            }
        }
        return false;
    }
}
