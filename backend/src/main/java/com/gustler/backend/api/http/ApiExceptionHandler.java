package com.gustler.backend.api.http;

import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(
        final ApiException exception
    ) {
        final ErrorCode code = exception.code();

        return new ResponseEntity<>(bodyOf(code, exception.getMessage()), noStore(), code.status());
    }

    private ErrorResponse bodyOf(
        final ErrorCode code,
        final String message
    ) {
        return new ErrorResponse(
            code,
            message,
            UUID.randomUUID().toString(),
            code.retryable()
        );
    }

    private HttpHeaders noStore() {
        final HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl(CacheControl.noStore());

        return headers;
    }
}
