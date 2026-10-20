package com.gustler.backend.api.chat.controller;

import java.util.HashMap;
import java.util.Map;

/**
 * 세션별 주소와 주소별 연결 수를 같은 잠금 안에서 갱신한다.
 * 중복 접수와 중복 해제는 연결 수를 늘리거나 줄이지 않는다.
 */
public final class ChatConnectionLimiter {
    private final int maxConnections;
    private final int maxConnectionsPerAddress;
    private final Map<String, String> addressBySession = new HashMap<>();
    private final Map<String, Integer> connectionsByAddress = new HashMap<>();

    public ChatConnectionLimiter(final int maxConnections, final int maxConnectionsPerAddress) {
        this.maxConnections = maxConnections;
        this.maxConnectionsPerAddress = maxConnectionsPerAddress;
    }

    /**
     * 전체·주소별 연결 제한 안에서 슬롯을 확보한다. 이미 등록된 세션은 중복 증가 없이 true이고 거부 시에는 상태를 바꾸지 않는다.
     */
    public synchronized boolean tryAcquire(String sessionId, String clientAddress) {
        if (addressBySession.containsKey(sessionId)) {
            return true;
        }
        if (addressBySession.size() >= maxConnections) {
            return false;
        }
        final int connections = connectionsByAddress.getOrDefault(clientAddress, 0);
        if (connections >= maxConnectionsPerAddress) {
            return false;
        }
        addressBySession.put(sessionId, clientAddress);
        connectionsByAddress.put(clientAddress, connections + 1);
        return true;
    }

    /**
     * 종료된 연결의 슬롯을 반환한다. 슬롯 확보와 반환은 연결 핸들러가 각각 한 번씩 처리한다.
     */
    public synchronized void release(String sessionId) {
        String clientAddress = addressBySession.remove(sessionId);
        if (clientAddress == null) {
            return;
        }
        connectionsByAddress.computeIfPresent(clientAddress, (address, connections) ->
            remainingConnectionsAfterRelease(connections)
        );
    }

    /** 마지막 연결을 해제하면 computeIfPresent가 주소 항목을 제거하도록 null을 반환한다. */
    private static Integer remainingConnectionsAfterRelease(final int connections) {
        if (connections <= 1) {
            return null;
        }
        return connections - 1;
    }
}
