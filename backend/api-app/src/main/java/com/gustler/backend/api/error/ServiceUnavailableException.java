package com.gustler.backend.api.error;

public class ServiceUnavailableException extends ApiException {

    public ServiceUnavailableException() {
        super(ErrorCode.SERVICE_UNAVAILABLE);
    }
}
