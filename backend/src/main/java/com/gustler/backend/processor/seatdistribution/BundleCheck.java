package com.gustler.backend.processor.seatdistribution;

/**
 * 계수 묶음을 ACTIVE 로 올리기 전에 통과해야 하는 검사.
 *
 * <p>하나라도 어긋나면 안 올린다. 목록을 여기 한 곳에 모아 둔 이유는 특징 계약이 아직 확정되지
 * 않아서다. v4-1 정본 3.3절이 정규화 상수 · 노선 열 · 정류장 순번 분모 · spline 매듭을 미결로
 * 뒀다. 확정되면 이 파일과 {@link BundleTensor} 만 고치면 된다.
 *
 * <p>상수 이름이 곧 거절 사유다. 무엇이 어긋나 안 올렸는지가 로그에 이 이름으로 남는다.
 */
enum BundleCheck {

    MANIFEST_IS_REGULAR_FILE("설명 파일이 일반 파일이 아니다"),
    WEIGHTS_IS_REGULAR_FILE("계수 파일이 일반 파일이 아니다"),
    MANIFEST_IS_UTF8_JSON("설명 파일이 UTF-8 JSON 이 아니다"),
    MANIFEST_HAS_NO_UNKNOWN_FIELD("설명 파일에 우리가 모르는 항목이 있다"),
    BUNDLE_SCHEMA_VERSION("번들 판 이름이 다르다"),
    MODEL_VERSION("모델 판 이름이 다르다"),
    FEATURE_CONTRACT_VERSION("특징 계약 판 이름이 없다"),
    ROUTE_REFERENCE("노선 참조 판 이름이나 요약값이 없다"),
    ROUTE_ORDER("노선 목록의 순서가 계약과 다르다"),
    HORIZON_STOPS("예보 거리 목록이 1정류장 앞부터 12정류장 앞까지가 아니다"),
    FEATURE_NAMES("계수 파일이 적은 열 이름과 순서가 우리 설계행렬과 다르다"),
    GOLDEN_VECTOR("계수 파일이 실은 대조 사례를 우리 계산이 재현하지 못한다"),
    WEIGHTS_HAS_NO_UNKNOWN_TENSOR("계수 파일에 우리가 읽을 줄 모르는 배열이 있다"),
    REQUIRED_TENSORS_PRESENT("좌석 분포를 만드는 데 필요한 배열이 없다"),
    TENSOR_SHAPE_AND_DATA_TYPE("배열의 크기나 자료형이 설명 파일과 다르다"),
    COEFFICIENTS_ARE_FINITE("계수에 NaN 이나 Infinity 가 있다"),
    FITTED_FLAGS_ARE_ZERO_OR_ONE("적합 여부 표시 배열에 0 과 1 이 아닌 값이 있다"),
    WEIGHTS_DIGEST("계수 파일의 요약값이 설명 파일과 다르다"),
    RELEASE_IDENTITY_DIGEST("출시 식별자가 특징 계약·노선 참조·계수 파일에 안 묶여 있다"),
    ;

    private final String rejection;

    BundleCheck(
        String rejection
    ) {
        this.rejection = rejection;
    }

    BundleRejectedException reject(
        String detail
    ) {
        return new BundleRejectedException("[%s] %s: %s".formatted(name(), rejection, detail));
    }

    void require(
        final boolean satisfied,
        String detail
    ) {
        if (!satisfied) {
            throw reject(detail);
        }
    }
}
