package com.gustler.backend.api.http;

public class ServiceUnavailableException extends ApiException {

    public ServiceUnavailableException() {
        super(ErrorCode.SERVICE_UNAVAILABLE);
    }
}
