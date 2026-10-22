package com.gustler.backend.api.chat.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * 채팅 세션 식별 정보로 방 안에서 사용할 작성자 식별값을 만든다.
 * 저장소나 메시지 전송은 수행하지 않는다.
 */
public final class ChatIdentityService {
    private static final String[] ADJECTIVES = {
        "졸린", "수다스러운", "용감한", "느긋한", "반짝이는", "신나는", "엉뚱한", "다정한",
        "배고픈", "명랑한", "부지런한", "설레는", "재빠른", "포근한", "궁금한", "씩씩한"
    };

    /**
     * 연결 식별 정보로 방 안에서 사용할 익명 식별자와 닉네임을 만든다. 원문 식별 정보는 응답에 노출하지 않는다.
     */
    public ChatIdentity derive(ChatRoom room, UUID clientSessionId) {
        byte[] digest = sha256(room.routeId() + ":" + clientSessionId);
        String authorId = Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(digest, 16));
        List<String> stops = room.nicknameStops();
        String adjective = ADJECTIVES[Byte.toUnsignedInt(digest[16]) % ADJECTIVES.length];
        String stop = stops.get(Byte.toUnsignedInt(digest[17]) % stops.size());
        return new ChatIdentity(authorId, adjective + " " + stop);
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
