package com.gustler.backend.maintenance;

import com.gustler.backend.forecasting.api.evaluation.EvaluationArchiveObjects;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** 승인 전에 읽은 원본 바이트만 복원에 전달한다. 원격 업로드나 이관은 지원하지 않는다. */
public final class LocalLesArchive implements EvaluationArchiveObjects {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private final byte[] content;
    private final String hash;

    private LocalLesArchive(byte[] content) {
        this.content = content;
        this.hash = Sha256.of(content);
    }

    public static LocalLesArchive read(Path path) {
        SecureFiles.requirePrivateRegularFile(path);
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] content = input.readNBytes(MAX_BYTES + 1);
            if (content.length == 0 || content.length > MAX_BYTES) {
                throw new MaintenanceException("LES_ARCHIVE_SIZE_INVALID");
            }
            return new LocalLesArchive(content);
        } catch (IOException failure) {
            throw new MaintenanceException("LES_ARCHIVE_READ_FAILED", failure);
        }
    }

    public String sha256() {
        return hash;
    }

    @Override
    public void download(
        String bucket,
        String key,
        Path destination,
        final long maxBytes
    ) {
        if (!"techcourse-project-2026".equals(bucket)
            || !("salmonbus-be/les-evaluation-archive/v1/" + hash + ".json").equals(key)) {
            throw new MaintenanceException("LES_ARCHIVE_DATABASE_HASH_MISMATCH");
        }
        if (content.length > maxBytes) {
            throw new MaintenanceException("LES_ARCHIVE_SIZE_INVALID");
        }
        try {
            Files.write(destination, content);
        } catch (IOException failure) {
            throw new MaintenanceException("LES_ARCHIVE_COPY_FAILED", failure);
        }
    }

    @Override
    public void putIfAbsent(
        String bucket,
        String key,
        Path source,
        String checksumSha256
    ) {
        throw new MaintenanceException("LES_RESTORE_UPLOAD_FORBIDDEN");
    }
}
