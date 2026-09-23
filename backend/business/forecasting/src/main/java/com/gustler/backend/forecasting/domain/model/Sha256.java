package com.gustler.backend.forecasting.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 파일 내용과 식별 정보 문자열의 SHA-256 해시를 계산한다. */
public final class Sha256 {

    private Sha256() {
    }

    public static String of(
        byte[] content
    ) {
        return HexFormat.of().formatHex(digest().digest(content));
    }

    public static String of(
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
