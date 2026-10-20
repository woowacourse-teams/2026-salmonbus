package com.gustler.backend.api.http;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * 요청 하나에 부여하는 식별자다. 응답으로 보낸 값과 로그에 남은 값이 같아야 한다.
 *
 * <p>클라이언트가 오류 응답으로 받은 {@code requestId} 로 서버 로그를 찾을 수 있어야 한다.
 * 그러려면 한 요청에서 하나를 생성해 여러 처리 경로가 함께 사용해야 한다. 오류를 처리할 때마다
 * 새로 생성하면 응답과 로그의 값이 달라져 해당 요청의 로그를 찾을 수 없다.
 *
 * <p>식별자는 {@link RequestIdFilter} 에서 생성한다. 다만 필터까지 전달되지 못하는 요청이 있다. 주소의
 * 퍼센트 인코딩이 깨지면 톰캣이 먼저 거절해 서블릿이 실행되지 않는다. 그때는 {@link
 * ContainerErrorResponseValve} 가 생성한다. 따라서 식별자를 읽을 때는 값이 없을 수도 있다는 점을 고려한다.
 */
public final class RequestId {
    /** 응답에 실어 보내는 헤더 이름. 본문의 requestId 와 같은 값이다. */
    public static final String HEADER = "X-Request-ID";

    /** 요청 안에서 식별자를 전달하는 속성 이름이다. 로그 문맥 키로도 같은 이름을 쓴다. */
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

        if (attached instanceof String value) {
            return value;
        }
        return create();
    }

    /**
     * 현재 처리 중인 요청의 식별자다. 요청 밖이면 새로 만든다.
     *
     * <p>{@link ApiExceptionHandler} 는 예외만 받고 요청을 받지 않는다. 메서드 서명을 늘리는 대신
     * 스프링의 요청 문맥에서 읽는다.
     */
    public static String ofCurrentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return create();
        }
        Object attached = attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);

        if (attached instanceof String value) {
            return value;
        }
        return create();
    }
}
