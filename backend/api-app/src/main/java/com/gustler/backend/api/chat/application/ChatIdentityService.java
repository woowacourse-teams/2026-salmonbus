package com.gustler.backend.api.chat.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

public final class ChatIdentityService {

    private static final String[] ADJECTIVES = {
        "졸린", "수다스러운", "용감한", "느긋한", "반짝이는", "신나는", "엉뚱한", "다정한",
        "배고픈", "명랑한", "부지런한", "설레는", "재빠른", "포근한", "궁금한", "씩씩한"
    };

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
