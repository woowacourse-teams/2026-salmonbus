package com.gustler.backend.api.http;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * 오류 응답에 항상 포함하는 헤더다.
 *
 * <p>오류 응답을 만드는 경로는 넷이다. 예외 처리기 · 컨테이너가 넘긴 {@code /error} · 톰캣 valve ·
 * 스프링이 먼저 처리한 예외가 같은 헤더를 설정해야 한다. 한 경로라도 빠지면 응답 형식이 달라진다.
 *
 * <p>{@code Content-Type} 을 명시한다. 명시하지 않으면 요청의 {@code Accept} 로 협상해
 * 변환기를 고르는데, JSON 을 허용하지 않는 요청은 사용할 변환기가 없어 본문이 사라진다.
 * 잘못된 routeId 를 {@code Accept: text/plain} 으로 호출하면 400 이 아니라 500 에 0바이트가 반환됐다.
 *
 * <p>{@code Allow} 나 {@code Retry-After} 처럼 스프링이 이미 설정한 헤더는 그대로 둔다.
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
