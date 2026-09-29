package com.gustler.backend.api.chat.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;

class ChatClientAddressInterceptorTest {

    private static final String REMOTE_ADDRESS = "198.51.100.9";

    private final ChatClientAddressInterceptor interceptor = new ChatClientAddressInterceptor();

    @Test
    void X_Forwarded_For의_마지막_값을_쓴다() {
        // when
        String address = clientAddress(List.of("203.0.113.1, 203.0.113.2, 192.0.2.44"));

        // then
        assertThat(address).isEqualTo("192.0.2.44");
    }

    @Test
    void 헤더가_여러_줄이면_마지막_줄의_마지막_값을_쓴다() {
        // when
        String address = clientAddress(List.of("203.0.113.1, 203.0.113.2", "192.0.2.10, 192.0.2.44"));

        // then
        assertThat(address).isEqualTo("192.0.2.44");
    }

    @Test
    void 값의_앞뒤_공백을_뗀다() {
        // when
        String address = clientAddress(List.of("203.0.113.1 ,   192.0.2.44   "));

        // then
        assertThat(address).isEqualTo("192.0.2.44");
    }

    @Test
    void 마지막_값이_비어_있으면_원격_주소를_쓴다() {
        // when
        String trailingComma = clientAddress(List.of("192.0.2.44, "));
        String blankLine = clientAddress(List.of("192.0.2.44", "   "));

        // then
        assertThat(trailingComma).isEqualTo(REMOTE_ADDRESS);
        assertThat(blankLine).isEqualTo(REMOTE_ADDRESS);
    }

    @Test
    void 헤더가_없으면_원격_주소를_쓴다() {
        // when
        String address = clientAddress(List.of());

        // then
        assertThat(address).isEqualTo(REMOTE_ADDRESS);
    }

    @Test
    void 핸드셰이크를_막지_않는다() {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chat/rooms/204000057/stream");
        Map<String, Object> attributes = new HashMap<>();

        // when
        final boolean proceed = interceptor.beforeHandshake(
            new ServletServerHttpRequest(request),
            new ServletServerHttpResponse(new MockHttpServletResponse()),
            mock(WebSocketHandler.class),
            attributes
        );

        // then
        assertThat(proceed).isTrue();
        assertThat(attributes).containsOnlyKeys(ChatClientAddressInterceptor.CLIENT_ADDRESS_ATTRIBUTE);
    }

    private String clientAddress(List<String> forwardedForLines) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chat/rooms/204000057/stream");
        request.setRemoteAddr(REMOTE_ADDRESS);
        request.setRemoteHost(REMOTE_ADDRESS);
        forwardedForLines.forEach(line -> request.addHeader("X-Forwarded-For", line));
        Map<String, Object> attributes = new HashMap<>();
        interceptor.beforeHandshake(
            new ServletServerHttpRequest(request),
            new ServletServerHttpResponse(new MockHttpServletResponse()),
            mock(WebSocketHandler.class),
            attributes
        );
        return (String) attributes.get(ChatClientAddressInterceptor.CLIENT_ADDRESS_ATTRIBUTE);
    }
}
