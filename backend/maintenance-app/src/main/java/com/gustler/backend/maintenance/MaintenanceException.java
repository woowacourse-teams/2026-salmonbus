package com.gustler.backend.maintenance;

/** 사용자 데이터나 credential 값을 섞지 않는 안정적인 실패 코드. */
public class MaintenanceException extends RuntimeException {

    private final String code;

    public MaintenanceException(
        String code
    ) {
        super(code);
        this.code = code;
    }

    public MaintenanceException(
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
