package com.gustler.backend.migration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

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
