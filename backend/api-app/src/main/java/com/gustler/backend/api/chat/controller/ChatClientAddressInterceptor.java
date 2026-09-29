package com.gustler.backend.api.chat.controller;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

public final class ChatClientAddressInterceptor implements HandshakeInterceptor {

    public static final String CLIENT_ADDRESS_ATTRIBUTE = ChatClientAddressInterceptor.class.getName() + ".clientAddress";
    private static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final String UNKNOWN_ADDRESS = "unknown";

    @Override
    public boolean beforeHandshake(
        ServerHttpRequest request,
        ServerHttpResponse response,
        WebSocketHandler wsHandler,
        Map<String, Object> attributes
    ) {
        attributes.put(CLIENT_ADDRESS_ATTRIBUTE, clientAddress(request));
        return true;
    }

    @Override
    public void afterHandshake(
        ServerHttpRequest request,
        ServerHttpResponse response,
        WebSocketHandler wsHandler,
        Exception exception
    ) {
    }

    static String hostAddress(InetSocketAddress remoteAddress) {
        if (remoteAddress == null) {
            return UNKNOWN_ADDRESS;
        }
        InetAddress address = remoteAddress.getAddress();
        return address == null ? remoteAddress.getHostString() : address.getHostAddress();
    }

    private String clientAddress(ServerHttpRequest request) {
        List<String> lines = request.getHeaders().getOrEmpty(FORWARDED_FOR);
        if (!lines.isEmpty()) {
            String lastLine = lines.getLast();
            String lastValue = lastLine.substring(lastLine.lastIndexOf(',') + 1).strip();
            if (!lastValue.isEmpty()) {
                return lastValue;
            }
        }
        return hostAddress(request.getRemoteAddress());
    }
}
