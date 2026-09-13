package com.gustler.backend.api.http;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * 오류 응답에 늘 붙는 헤더.
 *
 * <p>오류를 내는 자리가 넷이다. 예외 처리기 · 컨테이너가 넘긴 {@code /error} · 톰캣 valve ·
 * 스프링이 먼저 잡은 예외. <b>넷이 같은 헤더를 붙여야 한다.</b> 한 자리만 빠져도 그 경로로
 * 나가는 응답이 다른 모양이 된다.
 *
 * <p>{@code Content-Type} 을 여기서 박는다. 안 박으면 요청의 {@code Accept} 로 협상해서 쓸
 * 변환기를 고르는데, JSON 을 안 받겠다는 요청은 고를 변환기가 없어 <b>본문이 통째로 사라진다.</b>
 * 잘못된 routeId 를 {@code Accept: text/plain} 으로 부르면 400 이 아니라 500 에 0바이트가 나갔다.
 *
 * <p>{@code Allow} 나 {@code Retry-After} 처럼 스프링이 이미 실어 둔 헤더는 그대로 둔다.
 */
final class ErrorHeaders {

    private ErrorHeaders() {
    }

    static HttpHeaders of(
        MediaType contentType
    ) {
        return of(HttpHeaders.EMPTY, contentType);
    }

    static HttpHeaders of(
        HttpHeaders original,
        MediaType contentType
    ) {
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(original);
        headers.setCacheControl(CacheControl.noStore());
        headers.setContentType(contentType);

        return headers;
    }
}
