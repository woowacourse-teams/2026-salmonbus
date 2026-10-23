package com.gustler.backend.api.account.token;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 애플 재인증 탈퇴를 시작할 수 있게 하는 짧은 수명의 티켓을 만들고 검증한다.
 *
 * <p>탈퇴 시작은 브라우저 이동(GET)이라 CSRF 토큰을 실을 수 없다. 쿠키만으로 시작을 허락하면 다른 사이트가 링크 하나로 탈퇴
 * 흐름을 열 수 있고, 사용자는 애플 화면의 '계속'을 로그인으로 알고 누른다. 그래서 CSRF 로 보호되는 POST 로만 티켓을 받게 하고,
 * 시작은 그 티켓이 있을 때만 한다.
 *
 * <p>티켓은 {@code <회원ID>.<만료 초>.<HMAC>} 이고 5분 동안 유효하다. 서명 키는 액세스 토큰 비밀을 쓰되 메시지에 용도를
 * 넣어, 티켓과 액세스 토큰이 서로를 대신하지 못하게 한다. 서버에 저장하지 않는다.
 */
public final class WithdrawalTickets {
    static final Duration VALIDITY = Duration.ofMinutes(5);

    private static final String PURPOSE = "salmonbus-withdrawal-ticket-v1|";
    private static final String ALGORITHM = "HmacSHA256";
    private static final Pattern FORMAT = Pattern.compile("([A-Za-z0-9_-]{22})\\.([0-9]{1,12})\\.([A-Za-z0-9_-]{43})");
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SecretKeySpec key;
    private final Clock clock;

    public WithdrawalTickets(TokenProperties properties, Clock clock) {
        this.key = new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), ALGORITHM);
        this.clock = clock;
    }

    public String issue(UUID memberId) {
        final long expiresAt = clock.instant().plus(VALIDITY).getEpochSecond();
        return ticket(memberId, expiresAt);
    }

    /** 서명이 맞고 만료 전이면 회원 ID 를 돌려준다. 형식이 틀리거나 어떤 이유로든 검증에 실패하면 비어 있다. */
    public Optional<UUID> verify(String ticket) {
        if (ticket == null) {
            return Optional.empty();
        }
        Matcher matcher = FORMAT.matcher(ticket);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        final long expiresAt = Long.parseLong(matcher.group(2));
        if (expiresAt <= clock.instant().getEpochSecond()) {
            return Optional.empty();
        }
        UUID memberId = uuidOf(DECODER.decode(matcher.group(1)));
        byte[] expected = ticket(memberId, expiresAt).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, ticket.getBytes(StandardCharsets.US_ASCII))) {
            return Optional.empty();
        }
        return Optional.of(memberId);
    }

    private String ticket(UUID memberId, long expiresAt) {
        String id = ENCODER.encodeToString(bytesOf(memberId));
        String mac = ENCODER.encodeToString(mac(PURPOSE + memberId + "|" + expiresAt));
        return id + "." + expiresAt + "." + mac;
    }

    private byte[] mac(String message) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("탈퇴 티켓 서명을 만들지 못했다", exception);
        }
    }

    private static byte[] bytesOf(UUID memberId) {
        return ByteBuffer.allocate(16)
            .putLong(memberId.getMostSignificantBits())
            .putLong(memberId.getLeastSignificantBits())
            .array();
    }

    private static UUID uuidOf(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
