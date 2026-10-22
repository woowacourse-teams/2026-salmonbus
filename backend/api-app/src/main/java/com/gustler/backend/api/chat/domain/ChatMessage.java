package com.gustler.backend.api.chat.domain;

import java.time.Instant;

/**
 * 방과 클라이언트 메시지 식별에 연결된 작성자·본문·생성 시각이다.
 * 전송 재시도의 중복 판정에 사용한다.
 */
public record ChatMessage(
    String id,
    String roomId,
    String clientMessageId,
    String authorId,
    String nickname,
    String body,
    Instant createdAt
) {
}
