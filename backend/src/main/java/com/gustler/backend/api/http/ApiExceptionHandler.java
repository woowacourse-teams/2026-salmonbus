package com.gustler.backend.api.http;

import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(
        final ApiException exception
    ) {
        final ErrorCode code = exception.code();
        final HttpHeaders headers = noStore();
        exception.retryAfter().ifPresent(retryAfter ->
            headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter.toSeconds())));

        return new ResponseEntity<>(bodyOf(code, exception.getMessage()), headers, code.status());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(
        final Exception exception
    ) {
        return respond(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.message(),
            ErrorCode.INTERNAL_ERROR.status());
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
        final Exception exception,
        final Object body,
        final HttpHeaders headers,
        final HttpStatusCode status,
        final WebRequest request
    ) {
        final ErrorCode code = ErrorCode.of(status);

        return new ResponseEntity<>(bodyOf(code, code.message()), noStore(headers), status);
    }

    private ResponseEntity<ErrorResponse> respond(
        final ErrorCode code,
        final String message,
        final HttpStatusCode status
    ) {
        return new ResponseEntity<>(bodyOf(code, message), noStore(), status);
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
        return noStore(HttpHeaders.EMPTY);
    }

    private HttpHeaders noStore(
        final HttpHeaders original
    ) {
        final HttpHeaders headers = new HttpHeaders();
        headers.addAll(original);
        headers.setCacheControl(CacheControl.noStore());

        return headers;
    }
}
