package com.gustler.backend.migration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
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

    public static String of(
        Path path
    ) {
        MessageDigest digest = newDigest();
        try (InputStream input = Files.newInputStream(path);
            DigestInputStream hashing = new DigestInputStream(input, digest)) {
            hashing.transferTo(OutputStreams.discarding());
        } catch (IOException e) {
            throw new MigrationException("FILE_SHA256_READ_FAILED", e);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static boolean isDigest(
        String value
    ) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    public static void requireDigest(
        String value,
        String code
    ) {
        if (!isDigest(value)) {
            throw new MigrationException(code);
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(ALGORITHM + " unavailable", e);
        }
    }
}
