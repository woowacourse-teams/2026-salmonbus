package com.gustler.backend.api.http;

import com.gustler.backend.api.route.InvalidRouteIdException;
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
        final ErrorResponse response = new ErrorResponse(
            ErrorCode.INVALID_ROUTE_ID,
            exception.getMessage(),
            UUID.randomUUID().toString(),
            false
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .cacheControl(CacheControl.noStore())
            .body(response);
    }
}
