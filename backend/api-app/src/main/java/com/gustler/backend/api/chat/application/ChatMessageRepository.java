package com.gustler.backend.api.chat.application;

import com.gustler.backend.api.chat.application.ChatMessageRepository.SaveResult.Status;
import com.gustler.backend.api.chat.domain.ChatMessage;
import java.util.List;

/**
 * 채팅 메시지 식별·저장·최근 이력을 제공한다.
 * 같은 클라이언트 메시지의 재전송과 충돌은 저장 결과로 구분한다.
 */
public interface ChatMessageRepository {
    /**
     * 새 메시지에 사용할 식별자를 생성한다. 메시지 저장은 별도 save 호출에서 수행한다.
     */
    String nextId();

    /**
     * 메시지를 저장하며 최초 저장·중복·충돌을 구별한다. 중복 요청을 별개의 새 메시지로 저장하지 않는다.
     */
    SaveResult save(ChatMessage message);

    /**
     * 방의 최근 메시지를 지정 개수만큼 골라 오래된 순서로 반환한다. 같은 시각은 식별자 순서로 구별한다.
     */
    List<ChatMessage> findRecent(String roomId, int limit);

    record SaveResult(Status status, ChatMessage message) {
        public enum Status {
            CREATED,
            DUPLICATE,
            CONFLICT
        }
    }
}
