package com.gustler.backend.api.chat.controller;

import java.util.HashMap;
import java.util.Map;

public final class ChatConnectionLimiter {

    public static final int MAX_CONNECTIONS = 100;
    public static final int MAX_CONNECTIONS_PER_ADDRESS = 5;

    private final Map<String, String> addressBySession = new HashMap<>();
    private final Map<String, Integer> connectionsByAddress = new HashMap<>();

    public synchronized boolean tryAcquire(String sessionId, String clientAddress) {
        if (addressBySession.containsKey(sessionId)) {
            return true;
        }
        if (addressBySession.size() >= MAX_CONNECTIONS) {
            return false;
        }
        final int connections = connectionsByAddress.getOrDefault(clientAddress, 0);
        if (connections >= MAX_CONNECTIONS_PER_ADDRESS) {
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
