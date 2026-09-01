package com.gustler.backend.processor.seatdistribution;

/**
 * 계수 파일이 담는 값의 자료형.
 *
 * <p>둘뿐이다. 계수는 배정밀도 실수이고, 무엇이 적합됐는지 표시하는 것은 0 과 1 이다.
 * 계수를 단정밀도로 담으면 자릿수가 모자라 같은 입력에서 다른 확률이 나온다. 그래서
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
