package com.gustler.backend.member.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 리프레시 토큰을 서버 비밀로 서명해 만들고 검증한다.
 *
 * <p>토큰은 {@code <계통 ID 16바이트>.<세대>.<서명>} 이고 앞의 둘과 서명은 Base64 URL(패딩 없음)이다.
 * 서명은 {@code HMAC-SHA256(비밀, "refresh-v1|" + 계통 UUID + "|" + 세대)} 전체 32바이트다.
 * 같은 계통과 세대는 언제나 같은 토큰이 되므로 서버가 토큰을 저장하지 않고도 다시 만들어 돌려줄 수 있다.
 *
 * <p>검증은 받은 문자열을 계통과 세대로 풀어 다시 만든 토큰과 상수 시간으로 비교한다. 형식이 같은 다른 표기는 통과하지 못한다.
 * 형식이 어긋난 입력은 예외 없이 빈 값이 된다. 비밀과 토큰 원문은 {@code toString} 과 예외 메시지에 쓰지 않는다.
 */
public final class RefreshTokenCodec {
    private static final String ALGORITHM = "HmacSHA256";
    private static final String MESSAGE_PREFIX = "refresh-v1|";
    private static final int MAX_TOKEN_LENGTH = 100;
    private static final int UUID_BYTES = 16;
    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{22}\\.[0-9]{1,10}\\.[A-Za-z0-9_-]{43}");

    private final SecretKeySpec key;

    public RefreshTokenCodec(byte[] secret) {
        this.key = new SecretKeySpec(secret.clone(), ALGORITHM);
    }

    public String encode(UUID familyId, int generation) {
        if (generation < 0) {
            throw new IllegalArgumentException("세대는 0 이상이어야 한다");
        }
        return base64(toBytes(familyId)) + "." + generation + "." + base64(sign(familyId, generation));
    }

    /** 서명이 맞는 토큰만 계통과 세대를 돌려준다. */
    public Optional<RefreshTokenClaim> decode(String token) {
        if (token == null || token.length() > MAX_TOKEN_LENGTH || !FORMAT.matcher(token).matches()) {
            return Optional.empty();
        }
        String[] parts = token.split("\\.");
        try {
            UUID familyId = toUuid(Base64.getUrlDecoder().decode(parts[0]));
            long generation = Long.parseLong(parts[1]);
            if (generation > Integer.MAX_VALUE) {
                return Optional.empty();
            }
            String expected = encode(familyId, (int) generation);
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                token.getBytes(StandardCharsets.US_ASCII))) {
                return Optional.empty();
            }
            return Optional.of(new RefreshTokenClaim(familyId, (int) generation));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private byte[] sign(UUID familyId, int generation) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal((MESSAGE_PREFIX + familyId + "|" + generation).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new IllegalStateException("리프레시 토큰에 서명하지 못했다");
        }
    }

    private static String base64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] toBytes(UUID uuid) {
        return ByteBuffer.allocate(UUID_BYTES)
            .putLong(uuid.getMostSignificantBits())
            .putLong(uuid.getLeastSignificantBits())
            .array();
    }

    private static UUID toUuid(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    @Override
    public String toString() {
        return "RefreshTokenCodec[key=****]";
    }
}
