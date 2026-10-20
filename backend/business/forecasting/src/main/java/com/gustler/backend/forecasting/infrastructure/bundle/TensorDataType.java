package com.gustler.backend.forecasting.infrastructure.bundle;

/**
 * 계수 파일에 포함되는 값의 자료형.
 *
 * <p>계수는 배정밀도 실수이며 적합 여부는 0 과 1 로 표시한다. 지원하는 자료형은 둘뿐이다.
 * 계수를 단정밀도로 저장하면 자릿수가 모자라 같은 입력에서 다른 확률이 나오므로
 * 자료형이 다르면 읽지 않고 거절한다.
 */
enum TensorDataType {
    FLOAT64("F64", 8),
    UINT8("U8", 1),
    ;

    private final String name;
    private final int byteCount;

    TensorDataType(
        String name,
        final int byteCount
    ) {
        this.name = name;
        this.byteCount = byteCount;
    }

    String tensorName() {
        return name;
    }

    int byteCount() {
        return byteCount;
    }

    static TensorDataType of(
        String name
    ) {
        for (TensorDataType type : values()) {
            if (type.name.equals(name)) {
                return type;
            }
        }
        throw new BundleRejectedException("계수 파일이 읽을 수 없는 자료형을 담고 있다: " + name);
    }
}
