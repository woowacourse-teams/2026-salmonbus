package com.gustler.backend.api.chat.controller;

import java.util.HashMap;
import java.util.Map;

public final class ChatConnectionLimiter {

    private final int maxConnections;
    private final int maxConnectionsPerAddress;
    private final Map<String, String> addressBySession = new HashMap<>();
    private final Map<String, Integer> connectionsByAddress = new HashMap<>();

    public ChatConnectionLimiter(final int maxConnections, final int maxConnectionsPerAddress) {
        this.maxConnections = maxConnections;
        this.maxConnectionsPerAddress = maxConnectionsPerAddress;
    }

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

    public synchronized void release(String sessionId) {
        String clientAddress = addressBySession.remove(sessionId);
        if (clientAddress == null) {
            return;
        }
        connectionsByAddress.computeIfPresent(clientAddress, (address, connections) ->
            connections <= 1 ? null : connections - 1
        );
    }
}
