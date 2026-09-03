package com.gustler.backend.migration;

/** 사용자 데이터나 credential 값을 섞지 않는 안정적인 실패 코드. */
public class MigrationException extends RuntimeException {

    private final String code;

    public MigrationException(
        String code
    ) {
        super(code);
        this.code = code;
    }

    public MigrationException(
        String code,
        Throwable cause
    ) {
        super(code, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
