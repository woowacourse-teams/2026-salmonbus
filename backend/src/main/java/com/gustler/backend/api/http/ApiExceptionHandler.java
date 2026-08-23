package com.gustler.backend.api.http;

import com.gustler.backend.api.route.InvalidRouteIdException;
import com.gustler.backend.api.route.RouteNotFoundException;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(InvalidRouteIdException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRouteId(
        final InvalidRouteIdException exception
    ) {
        return createResponse(
            HttpStatus.BAD_REQUEST,
            ErrorCode.INVALID_ROUTE_ID,
            exception.getMessage(),
            false
        );
    }

    @ExceptionHandler(RouteNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleRouteNotFound(
        final RouteNotFoundException exception
    ) {
        return createResponse(
            HttpStatus.NOT_FOUND,
            ErrorCode.ROUTE_NOT_FOUND,
            exception.getMessage(),
            false
        );
    }

    private ResponseEntity<ErrorResponse> createResponse(
        final HttpStatus status,
        final ErrorCode code,
        final String message,
        final boolean retryable
    ) {
        final ErrorResponse response = new ErrorResponse(
            code,
            message,
            UUID.randomUUID().toString(),
            retryable
        );

        return ResponseEntity.status(status)
            .cacheControl(CacheControl.noStore())
            .body(response);
    }
}
