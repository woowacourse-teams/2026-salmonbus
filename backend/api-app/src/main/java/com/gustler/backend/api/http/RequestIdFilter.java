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
 * 요청마다 식별자를 하나 만들어 응답 헤더와 로그 문맥에 함께 싣는다.
 *
 * <p>제일 앞에 둔다. 뒤에 서는 필터가 던지는 예외도 같은 식별자로 남아야 한다.
 *
 * <p>{@code /error} 로 넘어가는 요청도 같은 식별자를 쓴다. 스프링이 그 경로를 부를 때 요청
 * 객체를 새로 만들지 않아서 붙여 둔 값이 그대로 따라간다. 그래서 {@code DispatcherType} 을
 * 안 가리고 한 번만 만든다.
 *
 * <p>로그 문맥에 넣는 것만으로는 로그 줄에 안 찍힌다. {@code application.yml} 의
 * {@code logging.pattern.correlation} 이 {@code %X{requestId}} 를 읽어 가야 찍힌다.
 * 둘 중 하나가 빠지면 클라이언트가 받은 식별자로 찾을 로그가 없다.
 *
 * <p>로그 문맥은 반드시 {@code finally} 에서 지운다. 스레드를 돌려 쓰는 곳이라 안 지우면
 * 다음 요청의 로그에 남의 식별자가 붙는다.
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
