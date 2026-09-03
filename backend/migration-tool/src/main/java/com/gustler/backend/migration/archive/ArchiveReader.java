package com.gustler.backend.migration.archive;

import com.gustler.backend.migration.MigrationException;
import com.gustler.backend.migration.SecureFiles;
import com.gustler.backend.migration.Sha256;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.DigestInputStream;
import java.util.HexFormat;
import java.util.function.BiConsumer;

public final class ArchiveReader {

    private static final int MAX_CANONICAL_RECORD_BYTES = 4 * 1024 * 1024;

    private ArchiveReader() {
    }

    public static void forEachRecord(
        Path directory,
        ArchiveManifest manifest,
        BiConsumer<ArchiveManifest.Shard, IndexedRecord> consumer
    ) {
        for (ArchiveManifest.Shard shard : manifest.shards()) {
            Path path = safeShardPath(directory, shard);
            verifyShardFile(path, shard);
            readShard(path, shard, consumer);
        }
    }

    private static Path safeShardPath(
        Path directory,
        ArchiveManifest.Shard shard
    ) {
        Path root = directory.toAbsolutePath().normalize();
        Path path = root.resolve(shard.file()).normalize();
        if (!path.startsWith(root.resolve("shards"))
            || Files.isSymbolicLink(path)
            || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("ARCHIVE_SHARD_PATH_INVALID");
        }
        SecureFiles.requirePrivateRegularFile(path);
        return path;
    }

    private static void verifyShardFile(
        Path path,
        ArchiveManifest.Shard shard
    ) {
        try {
            if (Files.size(path) != shard.compressedBytes()) {
                throw new MigrationException("ARCHIVE_SHARD_SIZE_MISMATCH");
            }
        } catch (IOException e) {
            throw new MigrationException("ARCHIVE_SHARD_SIZE_READ_FAILED", e);
        }
        if (!Sha256.of(path).equals(shard.sha256())) {
            throw new MigrationException("ARCHIVE_SHARD_DIGEST_MISMATCH");
        }
    }

    private static void readShard(
        Path path,
        ArchiveManifest.Shard shard,
        BiConsumer<ArchiveManifest.Shard, IndexedRecord> consumer
    ) {
        Process decompressor;
        try {
            decompressor = new ProcessBuilder("zstd", "-q", "-d", "--stdout", "--no-progress", path.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        } catch (IOException e) {
            throw new MigrationException("ZSTD_DECOMPRESSOR_START_FAILED", e);
        }
        int lineNumber = 0;
        long bytesRead = 0;
        MessageDigest digest = newDigest();
        try (InputStream input = new DigestInputStream(decompressor.getInputStream(), digest)) {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int next;
            while ((next = input.read()) >= 0) {
                bytesRead++;
                if (next == '\n') {
                    if (line.size() == 0) {
                        throw new MigrationException("ARCHIVE_SHARD_EMPTY_LINE");
                    }
                    lineNumber++;
                    byte[] canonical = line.toByteArray();
                    consumer.accept(shard, new IndexedRecord(
                        lineNumber, ArchiveRecord.parseCanonical(canonical), Sha256.of(canonical)));
                    line.reset();
                    continue;
                }
                if (next == '\r') {
                    throw new MigrationException("ARCHIVE_SHARD_CR_BYTE_FORBIDDEN");
                }
                line.write(next);
                if (line.size() > MAX_CANONICAL_RECORD_BYTES) {
                    throw new MigrationException("ARCHIVE_SHARD_RECORD_TOO_LARGE");
                }
            }
            if (line.size() != 0) {
                throw new MigrationException("ARCHIVE_SHARD_FINAL_LF_MISSING");
            }
            int exit = decompressor.waitFor();
            if (exit != 0) {
                throw new MigrationException("ZSTD_DECOMPRESSOR_FAILED");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MigrationException("ZSTD_DECOMPRESSOR_INTERRUPTED", e);
        } catch (IOException e) {
            throw new MigrationException("ARCHIVE_SHARD_READ_FAILED", e);
        } finally {
            if (decompressor.isAlive()) {
                decompressor.destroyForcibly();
            }
        }
        if (lineNumber != shard.batchCount()) {
            throw new MigrationException("ARCHIVE_SHARD_BATCH_COUNT_MISMATCH");
        }
        if (bytesRead != shard.uncompressedBytes()
            || !HexFormat.of().formatHex(digest.digest()).equals(shard.uncompressedSha256())) {
            throw new MigrationException("ARCHIVE_SHARD_UNCOMPRESSED_DIGEST_MISMATCH");
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record IndexedRecord(
        int lineNumber,
        ArchiveRecord record,
        String normalizedSha256
    ) {

        @Override
        public String toString() {
            return "IndexedRecord[lineNumber=" + lineNumber + ", record=***]";
        }
    }
}
