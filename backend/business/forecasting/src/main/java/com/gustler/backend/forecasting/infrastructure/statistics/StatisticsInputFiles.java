package com.gustler.backend.forecasting.infrastructure.statistics;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** 입력 파일의 바이트만 검사한다. 표본 건수와 참조의 완전성은 별도로 검사해야 한다. */
final class StatisticsInputFiles {

    private static final int BUFFER_SIZE = 64 * 1024;

    private StatisticsInputFiles() {
    }

    static void verify(Path directory, List<StatisticsInputFile> declarations) {
        if (directory == null || Files.isSymbolicLink(directory)
            || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("입력 파일 디렉터리가 없거나 일반 디렉터리가 아니다");
        }
        if (declarations == null || declarations.isEmpty()) {
            throw new IllegalArgumentException("입력 파일 목록이 필요하다");
        }
        Set<String> names = new HashSet<>();
        for (StatisticsInputFile file : declarations) {
            if (file == null || !names.add(file.name())) {
                throw new IllegalArgumentException("입력 파일 선언이 없거나 이름이 중복된다");
            }
        }
        for (StatisticsInputFile file : declarations) {
            verifyFile(directory.resolve(file.name()), file);
        }
    }

    private static void verifyFile(Path path, StatisticsInputFile declaration) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("입력 파일이 없거나 일반 파일이 아니다: " + declaration.name());
        }
        MessageDigest digest = sha256();
        try {
            if (Files.size(path) != declaration.byteCount()) {
                throw new IllegalArgumentException("입력 파일 크기가 다르다: " + declaration.name());
            }
            long remaining = declaration.byteCount();
            byte[] buffer = new byte[BUFFER_SIZE];
            try (InputStream input = Files.newInputStream(path)) {
                int size;
                while ((size = input.read(buffer)) != -1) {
                    if (size > remaining) {
                        throw new IllegalArgumentException("읽는 중 입력 파일 크기가 늘었다: " + declaration.name());
                    }
                    digest.update(buffer, 0, size);
                    remaining -= size;
                }
            }
            if (remaining != 0) {
                throw new IllegalArgumentException("입력 파일을 끝까지 읽지 못했다: " + declaration.name());
            }
            if (!HexFormat.of().formatHex(digest.digest()).equals(declaration.sha256())) {
                throw new IllegalArgumentException("입력 파일 SHA-256이 다르다: " + declaration.name());
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("입력 파일을 읽지 못했다: " + declaration.name(), exception);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없다", exception);
        }
    }
}
