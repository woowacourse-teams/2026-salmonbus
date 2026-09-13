package com.gustler.backend.api.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 컨트롤러까지 온 요청의 오류를 우리 오류 응답 형식으로 낸다.
 *
 * <p><b>여기를 안 지나는 오류가 있다.</b> 컨테이너가 {@code sendError} 로 넘긴 것은
 * {@link ApiErrorController} 가, 톰캣이 주소를 해석하다 거절한 것은
 * {@link ContainerErrorResponseValve} 가 맡는다. 셋이 {@link ErrorHeaders} 와 {@link ErrorCode}
 * 를 같이 써서 어느 길로 나가든 같은 모양이 되게 한다.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(
        ApiException exception
    ) {
        ErrorCode code = exception.code();
        HttpHeaders headers = errorHeaders();
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
        return respond(ErrorCode.SERVICE_UNAVAILABLE);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(
        Exception exception
    ) {
        return respond(ErrorCode.INTERNAL_ERROR);
    }

    /**
     * 스프링이 먼저 잡는 예외의 응답도 같은 모양으로 낸다.
     *
     * <p>스프링이 이미 실어 둔 {@code Allow} 같은 헤더는 그대로 두고 우리 것만 얹는다.
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
            bodyOf(code, code.message()),
            ErrorHeaders.of(headers, MediaType.APPLICATION_JSON),
            status);
    }

    private ResponseEntity<ErrorResponse> respond(
        ErrorCode code
    ) {
        return new ResponseEntity<>(bodyOf(code, code.message()), errorHeaders(), code.status());
    }

    /**
     * 응답에 실을 값을 만들면서 같은 식별자로 로그도 남긴다.
     *
     * <p>안 남기면 클라이언트가 받은 {@code requestId} 로 찾을 로그가 없다. 그러면 그 필드를
     * 응답에 실은 뜻이 절반만 산다.
     *
     * <p>서버 잘못은 {@code WARN}, 요청 잘못은 {@code INFO} 로 남긴다. 요청 잘못까지 경고로
     * 올리면 잘못된 routeId 하나에 경고가 쌓여서 진짜 경고가 묻힌다.
     */
    private ErrorResponse bodyOf(
        ErrorCode code,
        String message
    ) {
        String requestId = RequestId.ofCurrentRequest();
        if (code.status().is5xxServerError()) {
            log.warn("오류로 응답한다. 오류코드={} requestId={}", code.name(), requestId);
        } else {
            log.info("오류로 응답한다. 오류코드={} requestId={}", code.name(), requestId);
        }

        return new ErrorResponse(code, message, requestId);
    }

    private HttpHeaders errorHeaders() {
        return ErrorHeaders.of(MediaType.APPLICATION_JSON);
    }
}
