package com.gustler.backend.archive;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** PostgreSQL JSON 원문을 변환하지 않고 줄 단위로 압축한다. */
public final class ArchiveFiles {
    public static final int MAX_BYTES = 16 * 1024 * 1024;

    private ArchiveFiles() {
    }

    public static byte[] encode(List<String> rows) {
        byte[] plain = String.join("\n", rows).concat("\n").getBytes(StandardCharsets.UTF_8);
        if (plain.length > MAX_BYTES) {
            throw new IllegalArgumentException("원본 페이지가 16MiB를 넘는다");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
                gzip.write(plain);
            }
            return bytes.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    public static List<String> decode(byte[] bytes) {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            byte[] plain = gzip.readNBytes(MAX_BYTES + 1);
            if (plain.length > MAX_BYTES) {
                throw new IllegalArgumentException("압축 해제 한도 초과");
            }
            return new String(plain, StandardCharsets.UTF_8).lines().toList();
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    public static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    public static String digest(byte[] bytes) {
        return HexFormat.of().formatHex(sha256(bytes));
    }
}
