package com.gustler.backend.processor.seatdistribution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 파일과 신원 문자열의 요약값. */
final class Sha256 {

    private Sha256() {
    }

    static String of(
        byte[] content
    ) {
        return HexFormat.of().formatHex(digest().digest(content));
    }

    static String of(
        String text
    ) {
        return of(text.getBytes(StandardCharsets.UTF_8));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 을 못 찾는다", error);
        }
    }
}
