package com.gustler.backend.api.http;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * CSRF 토큰 값을 요청 헤더에서만 읽는다.
 *
 * <p>기본 처리기는 헤더가 없으면 폼 파라미터({@code _csrf})도 본다. 파라미터는 다른 사이트의 일반 폼 전송으로도 채울 수 있어
 * 쿠키와 헤더를 맞춰 보내는 이중 제출 방식의 의미를 약하게 만든다.
 */
final class HeaderOnlyCsrfTokenRequestHandler extends CsrfTokenRequestAttributeHandler {
    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        return request.getHeader(csrfToken.getHeaderName());
    }
}
