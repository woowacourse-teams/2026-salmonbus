package com.gustler.backend.api.chat.application;

import java.util.List;

/**
 * 채팅방 카탈로그에 필요한 현재 노선 표시명과 정류장 이름을 전달한다.
 */
public record ChatRoute(String routeId, String displayName, List<String> stopNames) {
    public ChatRoute {
        stopNames = List.copyOf(stopNames);
    }
}
