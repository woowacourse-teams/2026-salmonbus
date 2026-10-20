package com.gustler.backend.api.http;

import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;
import com.gustler.backend.api.error.ErrorResponse;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 컨트롤러까지 온 요청의 오류를 서비스의 오류 응답 형식으로 반환한다.
 *
 * <p>이 처리기를 거치지 않는 오류가 있다. 컨테이너가 {@code sendError} 로 넘긴 것은
 * {@link ApiErrorController} 가, 톰캣이 주소를 해석하다 거절한 것은
 * {@link ContainerErrorResponseValve} 가 맡는다. 세 처리기는 {@link ErrorHeaders} 와 {@link ErrorCode}
 * 를 함께 사용해 처리 경로와 관계없이 같은 형식으로 응답한다.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    /**
     * 업무 예외를 기존 오류 코드·메시지·requestId 응답으로 매핑한다. 예외 원문을 클라이언트 응답에 노출하지 않는다.
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(
        ApiException exception
    ) {
        ErrorCode code = exception.code();
        HttpHeaders headers = errorHeaders();
        exception.retryAfter().ifPresent(retryAfter ->
            headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter.toSeconds())));

        return new ResponseEntity<>(bodyOf(code, exception.getMessage(), code.status()
            .value(), exception), headers, code.status());
    }

    @ExceptionHandler({
        CannotCreateTransactionException.class,
        JpaSystemException.class,
        DataAccessResourceFailureException.class,
        QueryTimeoutException.class
    })
    public ResponseEntity<ErrorResponse> handleDatabaseUnavailable(
        RuntimeException exception
    ) {
        return respond(ErrorCode.SERVICE_UNAVAILABLE, exception);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(
        Exception exception
    ) {
        return respond(ErrorCode.INTERNAL_ERROR, exception);
    }

    /**
     * 스프링이 먼저 처리하는 예외의 응답도 같은 형식으로 반환한다.
     *
     * <p>스프링이 설정한 {@code Allow} 같은 헤더는 유지하고 서비스 오류 헤더만 추가한다.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
        Exception exception,
        Object body,
        HttpHeaders headers,
        HttpStatusCode status,
        WebRequest request
    ) {
        ErrorCode code = ErrorCode.of(status);

        return new ResponseEntity<>(
            bodyOf(code, code.message(), status.value(), exception),
            ErrorHeaders.of(headers, MediaType.APPLICATION_JSON),
            status);
    }

    private ResponseEntity<ErrorResponse> respond(
        ErrorCode code, Throwable exception
    ) {
        return new ResponseEntity<>(bodyOf(code, code.message(), code.status()
            .value(), exception), errorHeaders(), code.status());
    }

    /**
     * 응답 값을 만들면서 같은 식별자로 로그를 남긴다.
     *
     * <p>로그를 남기지 않으면 클라이언트가 받은 {@code requestId} 로 서버 로그를 찾을 수 없어
     * 응답에 식별자를 포함하는 목적을 충분히 충족하지 못한다.
     *
     * <p>서버 오류는 {@code WARN}, 요청 오류는 {@code INFO} 로 남긴다. 요청 오류까지 경고로
     * 기록하면 잘못된 routeId 하나로 경고가 쌓여 실제 대응이 필요한 경고를 찾기 어려워진다.
     */
    private ErrorResponse bodyOf(
        ErrorCode code,
        String message,
        final int status,
        Throwable failure
    ) {
        String requestId = RequestId.ofCurrentRequest();
        ApiFailureLog.write(status, code, requestId, failure);

        return new ErrorResponse(code, message, requestId);
    }

    private HttpHeaders errorHeaders() {
        return ErrorHeaders.of(MediaType.APPLICATION_JSON);
    }
}
