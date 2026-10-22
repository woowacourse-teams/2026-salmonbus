package com.gustler.backend.api.http;

import com.gustler.backend.api.error.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ErrorReportValve;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * 톰캣이 스프링을 거치지 않고 반환하는 응답도 서비스의 오류 응답 형식으로 반환한다.
 *
 * <p>요청이 항상 컨트롤러까지 전달되지는 않는다. 주소의 퍼센트 인코딩이 깨져 있으면 톰캣이 주소를
 * 해석하다 400 으로 거절하며, 그때는 {@link ApiExceptionHandler} 가 호출되지 않는다.
 * 기본값으로 두면 {@code Content-Type: text/html} 로 톰캣 오류 화면이 반환된다.
 * api 문서는 오류 응답을 {@code code} · {@code message} · {@code requestId} 로 명시하고,
 * FE 는 해당 응답에서 {@code response.json()} 을 호출한다.
 *
 * <p>오류 코드는 {@link ErrorCode#of} 가 정한다. 컨트롤러를 거친 오류와 같은 매핑을 사용해야
 * 같은 상태 코드에 서로 다른 오류 코드가 반환되지 않는다.
 *
 * <p>이 단계에는 스프링 메시지 변환기가 없어 본문을 직접 작성한다. 응답에 담는 값
 * 셋은 모두 서버가 생성하므로 따옴표나 역슬래시가 포함될 여지는 없지만, 값이 바뀌어도 형식이
 * 깨지지 않도록 최소한의 escape 를 적용한다.
 *
 * <p>요청 식별자를 응답 헤더와 로그에 함께 남긴다. 이 경로의 요청은 {@link RequestIdFilter}
 * 를 통과하지 못했을 수 있어 그때는 새로 만든다. 식별자를 기록하지 않으면 클라이언트가 받은 값으로 서버
 * 로그를 찾을 수 없다. 이 경로의 거절은 다른 곳에 기록되지 않는다.
 */
public class ContainerErrorResponseValve extends ErrorReportValve {
    private static final Logger LOGGER = LoggerFactory.getLogger(ContainerErrorResponseValve.class);

    private static final int SMALLEST_ERROR_STATUS = 400;

    public ContainerErrorResponseValve() {
        setShowReport(false);
        setShowServerInfo(false);
    }

    @Override
    protected void report(
        Request request,
        Response response,
        Throwable throwable
    ) {
        final int status = response.getStatus();
        if (status < SMALLEST_ERROR_STATUS || response.getContentWritten() > 0) {
            return;
        }
        if (!response.setErrorReported()) {
            return;
        }

        ErrorCode code = ErrorCode.of(HttpStatus.valueOf(status));
        String requestId = requestIdOf(request);
        LOGGER.warn("컨테이너가 요청을 거절했다. 상태={} 오류코드={} requestId={}",
            status, code.name(), requestId);
        write(response, requestId, body(code, requestId));
    }

    /** 필터를 통과하지 못한 요청이면 식별자를 생성하고, 통과했다면 기존 값을 사용한다. */
    private String requestIdOf(
        Request request
    ) {
        if (request == null) {
            return RequestId.create();
        }
        return RequestId.of(request);
    }

    private String body(
        ErrorCode code,
        String requestId
    ) {
        return "{\"code\":\"%s\",\"message\":\"%s\",\"requestId\":\"%s\"}".formatted(
            escaped(code.name()),
            escaped(code.message()),
            escaped(requestId));
    }

    private static String escaped(
        String value
    ) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void write(
        Response response,
        String requestId,
        String body
    ) {
        try {
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8);
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setHeader(RequestId.HEADER, requestId);
            Writer writer = response.getReporter();
            if (writer != null) {
                writer.write(body);
                response.finishResponse();
            }
        } catch (IOException | IllegalStateException error) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }
}
