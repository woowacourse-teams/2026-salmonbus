package com.gustler.backend.api.http;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * 요청 하나에 붙는 식별자. 응답에 실어 보낸 값과 로그에 남은 값이 같아야 한다.
 *
 * <p>클라이언트가 오류 응답으로 받은 {@code requestId} 로 서버 로그를 찾을 수 있어야 한다.
 * 그러려면 <b>한 요청에 하나만 만들어 여러 자리가 나눠 써야</b> 한다. 오류를 내는 자리마다
 * 새로 만들면 응답에 실린 값과 로그에 남은 값이 달라져서 아무것도 못 찾는다.
 *
 * <p>만드는 자리는 {@link RequestIdFilter} 다. 다만 필터까지 못 오는 요청이 있다. 주소의
 * 퍼센트 인코딩이 깨지면 톰캣이 먼저 거절해서 서블릿이 안 돈다. 그때는 {@link
 * ContainerErrorResponseValve} 가 만든다. 그래서 읽는 쪽은 <b>없을 수도 있다고 보고</b> 읽는다.
 */
public final class RequestId {

    /** 응답에 실어 보내는 헤더 이름. 본문의 requestId 와 같은 값이다. */
    public static final String HEADER = "X-Request-ID";

    /** 요청 하나 안에서 값을 나르는 자리. 로그 문맥 키로도 같은 이름을 쓴다. */
    public static final String ATTRIBUTE = "requestId";

    private RequestId() {
    }

    public static String create() {
        return UUID.randomUUID().toString();
    }

    /** 이 요청에 이미 붙은 식별자. 없으면 새로 만든다. */
    public static String of(
        HttpServletRequest request
    ) {
        Object attached = request.getAttribute(ATTRIBUTE);

        return attached instanceof String value ? value : create();
    }

    /**
     * 지금 돌고 있는 요청의 식별자. 요청 밖이면 새로 만든다.
     *
     * <p>{@link ApiExceptionHandler} 는 예외만 받고 요청을 안 받는다. 서명을 늘리는 대신
     * 스프링이 들고 있는 요청 문맥에서 꺼낸다.
     */
    public static String ofCurrentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return create();
        }
        Object attached = attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);

        return attached instanceof String value ? value : create();
    }
}
