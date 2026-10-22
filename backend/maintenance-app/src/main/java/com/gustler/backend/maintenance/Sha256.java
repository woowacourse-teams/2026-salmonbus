package com.gustler.backend.maintenance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 유지보수 자료의 동일성을 대조할 SHA-256 값을 계산한다.
 * 입력 자료 자체를 출력하지 않는다.
 */
public final class Sha256 {
    private static final String ALGORITHM = "SHA-256";

    private Sha256() {
    }

    public static String of(
        String value
    ) {
        return of(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String of(
        byte[] value
    ) {
        MessageDigest digest = newDigest();
        digest.update(value);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(ALGORITHM + " unavailable", e);
        }
    }
}
