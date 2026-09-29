package com.gustler.backend.api.chat.application;

import java.util.List;

public record ChatRoom(String routeId, String displayName, List<String> nicknameStops) {

    public ChatRoom {
        nicknameStops = List.copyOf(nicknameStops);
        if (nicknameStops.isEmpty()) {
            throw new IllegalArgumentException("nicknameStops must not be empty");
        }
    }
}
