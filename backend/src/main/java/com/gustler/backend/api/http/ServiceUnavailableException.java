package com.gustler.backend.api.http;

public class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException() {
        super("일시적인 서버 장애가 발생했습니다.");
    }
}
