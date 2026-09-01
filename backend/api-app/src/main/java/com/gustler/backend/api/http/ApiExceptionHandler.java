package com.gustler.backend.api.http;

import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(
        ApiException exception
    ) {
        ErrorCode code = exception.code();
        HttpHeaders headers = noStore();
        exception.retryAfter().ifPresent(retryAfter ->
            headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter.toSeconds())));

        return new ResponseEntity<>(bodyOf(code, exception.getMessage()), headers, code.status());
    }

    @ExceptionHandler({
        CannotCreateTransactionException.class,
        JpaSystemException.class
    })
    public ResponseEntity<ErrorResponse> handleDatabaseUnavailable(
        RuntimeException exception
    ) {
        return respond(
            ErrorCode.SERVICE_UNAVAILABLE,
            ErrorCode.SERVICE_UNAVAILABLE.message(),
            ErrorCode.SERVICE_UNAVAILABLE.status()
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(
        Exception exception
    ) {
        return respond(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.message(),
            ErrorCode.INTERNAL_ERROR.status());
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
        Exception exception,
        Object body,
        HttpHeaders headers,
        HttpStatusCode status,
        WebRequest request
    ) {
        ErrorCode code = ErrorCode.of(status);

        return new ResponseEntity<>(bodyOf(code, code.message()), noStore(headers), status);
    }

    private ResponseEntity<ErrorResponse> respond(
        ErrorCode code,
        String message,
        HttpStatusCode status
    ) {
        return new ResponseEntity<>(bodyOf(code, message), noStore(), status);
    }

    private ErrorResponse bodyOf(
        ErrorCode code,
        String message
    ) {
        return new ErrorResponse(
            code,
            message,
            UUID.randomUUID().toString()
        );
    }

    private HttpHeaders noStore() {
        return noStore(HttpHeaders.EMPTY);
    }

    private HttpHeaders noStore(
        HttpHeaders original
    ) {
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(original);
        headers.setCacheControl(CacheControl.noStore());

        return headers;
    }
}
