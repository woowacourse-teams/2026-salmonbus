package com.gustler.backend.api.chat.application;

import java.util.List;

/**
 * 현재 노선과 정류장 표시명을 채팅 카탈로그에 제공하는 읽기 경계다.
 */
public interface ChatRouteSource {
    /**
     * 현재 노선과 정류장 표시명을 채팅방 카탈로그 입력으로 읽는다. 공개 표시값만 전달한다.
     */
    List<ChatRoute> findCurrentRoutes();
}
