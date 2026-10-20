package com.gustler.backend.api.chat.application;

import java.util.List;

/**
 * 현재 공개 노선의 채팅방 식별과 별명 후보를 보관한다.
 */
public record ChatRoom(String routeId, String displayName, List<String> nicknameStops) {
    public ChatRoom {
        nicknameStops = List.copyOf(nicknameStops);
        if (nicknameStops.isEmpty()) {
            throw new IllegalArgumentException("nicknameStops must not be empty");
        }
    }
}
