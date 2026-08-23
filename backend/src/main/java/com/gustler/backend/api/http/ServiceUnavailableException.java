package com.gustler.backend.api.http;

public class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException() {
        super("temporary failure");
    }
}
