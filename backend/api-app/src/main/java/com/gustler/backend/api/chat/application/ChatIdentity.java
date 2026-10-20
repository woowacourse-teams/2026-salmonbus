package com.gustler.backend.api.chat.application;

/**
 * 한 채팅방 세션의 작성자 식별값과 표시할 별명을 전달한다.
 * 원본 연결 인증 정보는 전송하지 않는다.
 */
public record ChatIdentity(String authorId, String nickname) {
}
