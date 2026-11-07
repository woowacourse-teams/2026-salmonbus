package com.gustler.backend.forecasting.api.les;

/** CLI에는 이 오류 코드만 공개한다. 원본 예외에는 파일·연결 정보가 포함될 수 있다. */
public final class LesHistoryBootstrapException extends RuntimeException {
    private final String code;

    public LesHistoryBootstrapException(String code, Throwable cause) {
        super(code, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
