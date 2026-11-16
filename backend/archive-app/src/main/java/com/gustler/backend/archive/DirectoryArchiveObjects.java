package com.gustler.backend.archive;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 파일을 서버의 폴더에 둔다. S3를 쓰지 않는 환경에서도 내보내기가 같은 순서(쓰기, 다시 읽어 확인, 장부, 떼어 지우기)로 돌게 한다.
 * 키는 폴더 아래의 상대 경로이고 폴더 밖을 가리키는 키는 받지 않는다. 파일을 계속 보관하지 않으며, DB에서 지운 줄의 파일은
 * {@link #release}가 지운다.
 */
public final class DirectoryArchiveObjects implements ArchiveObjects {
    private final Path root;

    public DirectoryArchiveObjects(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public void putVerified(String key, byte[] bytes) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            if (Files.notExists(target)) {
                writeWhole(target, bytes);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        if (!Arrays.equals(bytes, read(key, bytes.length))) {
            throw new IllegalStateException("폴더에서 다시 읽은 값이 쓴 원본과 다르다");
        }
    }

    @Override
    public byte[] read(String key, final int limit) {
        if (limit < 1 || limit > ArchiveFiles.MAX_BYTES) {
            throw new IllegalArgumentException("파일 읽기 한도");
        }
        try (InputStream stream = Files.newInputStream(resolve(key))) {
            byte[] bytes = stream.readNBytes(limit + 1);
            if (bytes.length > limit) {
                throw new IllegalStateException("파일 크기 한도 초과");
            }
            return bytes;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    @Override
    public void release(String prefix) {
        Path directory = resolve(prefix);
        if (Files.notExists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            List<Path> deepestFirst = paths.sorted(Comparator.reverseOrder()).toList();
            for (Path path : deepestFirst) {
                Files.delete(path);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** 다 쓴 파일만 보이도록 같은 폴더의 임시 파일에 쓴 뒤 이름을 바꾼다. */
    private static void writeWhole(Path target, byte[] bytes) throws IOException {
        Path partial = Files.createTempFile(target.getParent(), ".partial-", "");
        try {
            Files.write(partial, bytes);
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    private Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root) || path.equals(root)) {
            throw new IllegalArgumentException("폴더 밖을 가리키는 키");
        }
        return path;
    }
}
