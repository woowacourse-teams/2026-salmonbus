package com.gustler.backend.api.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청마다 식별자를 하나 만들어 응답 헤더와 로그 문맥에 함께 기록한다.
 *
 * <p>필터 체인의 가장 앞에 둔다. 뒤에서 실행되는 필터가 던진 예외도 같은 식별자로 기록돼야 한다.
 *
 * <p>{@code /error} 로 전달되는 요청도 같은 식별자를 쓴다. 스프링은 해당 경로를 호출할 때 요청
 * 객체를 새로 만들지 않으므로 기존 값이 유지된다. 따라서 {@code DispatcherType} 을
 * 구분하지 않고 한 번만 생성한다.
 *
 * <p>로그 문맥에 저장하는 것만으로는 로그에 출력되지 않는다. {@code application.yml} 의
 * {@code logging.pattern.correlation} 이 {@code %X{requestId}} 를 읽어야 한다.
 * 둘 중 하나가 빠지면 클라이언트가 받은 식별자로 서버 로그를 찾을 수 없다.
 *
 * <p>로그 문맥은 반드시 {@code finally} 에서 지운다. 스레드를 재사용하므로 지우지 않으면
 * 다음 요청의 로그에 이전 요청의 식별자가 남는다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain chain
    ) throws ServletException, IOException {
        String requestId = RequestId.create();
        request.setAttribute(RequestId.ATTRIBUTE, requestId);
        response.setHeader(RequestId.HEADER, requestId);
        MDC.put(RequestId.ATTRIBUTE, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(RequestId.ATTRIBUTE);
        }
    }

    /** {@code /error} 로 다시 들어오는 요청에도 같은 식별자를 유지한다. */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }
}
