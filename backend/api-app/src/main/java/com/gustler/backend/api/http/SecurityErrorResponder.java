package com.gustler.backend.api.http;

import com.gustler.backend.api.error.CsrfRejectedException;
import com.gustler.backend.api.error.ErrorCode;
import com.gustler.backend.api.error.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import tools.jackson.databind.ObjectMapper;

/**
 * 보안 필터가 거절한 요청도 서비스 오류 응답 형식으로 반환한다.
 *
 * <p>보안 필터는 컨트롤러보다 먼저 실행되므로 {@link ApiExceptionHandler} 를 거치지 않는다. 같은
 * {@code code}·{@code message}·{@code requestId} 본문과 {@code no-store} 를 여기서 직접 쓴다.
 *
 * <p>403 은 쓰지 않는다. CloudFront 가 403 을 {@code index.html} 200 으로 바꿔 클라이언트가 오류를
 * 알 수 없게 된다. CSRF 검증 실패는 400 {@code CSRF_REJECTED}, 그 밖의 거절은 401
 * {@code UNAUTHENTICATED} 로 반환한다.
 */
class SecurityErrorResponder implements AuthenticationEntryPoint, AccessDeniedHandler {
    private final ObjectMapper objectMapper;

    SecurityErrorResponder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(
        HttpServletRequest request,
        HttpServletResponse response,
        AuthenticationException exception
    ) throws IOException {
        respond(request, response, ErrorCode.UNAUTHENTICATED, exception);
    }

    @Override
    public void handle(
        HttpServletRequest request,
        HttpServletResponse response,
        AccessDeniedException exception
    ) throws IOException {
        if (exception instanceof CsrfException) {
            CsrfRejectedException rejected = new CsrfRejectedException();
            rejected.initCause(exception);
            respond(request, response, rejected.code(), rejected);
            return;
        }
        respond(request, response, ErrorCode.UNAUTHENTICATED, exception);
    }

    private void respond(
        HttpServletRequest request,
        HttpServletResponse response,
        ErrorCode code,
        Throwable failure
    ) throws IOException {
        String requestId = RequestId.of(request);
        ApiFailureLog.write(code.status().value(), code, requestId, failure);

        response.setStatus(code.status().value());
        ErrorHeaders.of(MediaType.APPLICATION_JSON)
            .forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
        response.setHeader(RequestId.HEADER, requestId);
        response.setCharacterEncoding(StandardCharsets.UTF_8);
        String body = objectMapper.writeValueAsString(new ErrorResponse(code, code.message(), requestId));
        response.getWriter().write(body);
    }
}
