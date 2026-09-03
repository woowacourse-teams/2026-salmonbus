package com.gustler.backend.migration.archive;

import com.gustler.backend.migration.MigrationException;
import com.gustler.backend.migration.SecureFiles;
import com.gustler.backend.migration.Sha256;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** KST date/batch boundary를 지키며 zstd level 3 content-addressed shard를 쓴다. */
public final class ShardWriter implements AutoCloseable {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final long MAX_UNCOMPRESSED_BYTES = 64L * 1024 * 1024;
    private static final long MAX_COMPRESSED_BYTES = 16L * 1024 * 1024;

    private final Path archiveDirectory;
    private final Path shardsDirectory;
    private final int maxBatches;
    private final List<ArchiveManifest.Shard> shards = new ArrayList<>();
    private final Map<String, Integer> nextOrdinalByDate = new TreeMap<>();
    private Path temporary;
    private Process compressor;
    private OutputStream output;
    private MessageDigest uncompressedDigest;
    private String kstDate;
    private int shardOrdinal;
    private int batches;
    private long observations;
    private long uncompressedBytes;
    private Instant collectedFrom;
    private Instant collectedThrough;

    public ShardWriter(
        Path archiveDirectory,
        int maxBatches
    ) {
        if (maxBatches < 1) {
            throw new MigrationException("ARCHIVE_SHARD_BATCH_LIMIT_INVALID");
        }
        this.archiveDirectory = archiveDirectory;
        this.shardsDirectory = archiveDirectory.resolve("shards");
        this.maxBatches = maxBatches;
        SecureFiles.createPrivateDirectory(archiveDirectory);
        SecureFiles.createPrivateDirectory(shardsDirectory);
    }

    public void append(
        ArchiveRecord record
    ) {
        byte[] canonical = record.canonicalBytes();
        String recordDate = record.batch().responseReceivedAt().atZone(KST).toLocalDate().toString();
        long nextBytes = canonical.length + 1L;
        if (output != null && (!recordDate.equals(kstDate)
            || batches == maxBatches || uncompressedBytes + nextBytes > MAX_UNCOMPRESSED_BYTES)) {
            finishShard();
        }
        ensureOpen(recordDate);
        try {
            output.write(canonical);
            output.write('\n');
        } catch (IOException e) {
            throw new MigrationException("ARCHIVE_SHARD_WRITE_FAILED", e);
        }
        batches++;
        observations += record.observations().size();
        uncompressedBytes += nextBytes;
        Instant collectedAt = record.batch().responseReceivedAt();
        collectedFrom = collectedFrom == null || collectedAt.isBefore(collectedFrom) ? collectedAt : collectedFrom;
        collectedThrough = collectedThrough == null || collectedAt.isAfter(collectedThrough)
            ? collectedAt : collectedThrough;
    }

    public List<ArchiveManifest.Shard> shards() {
        finishShard();
        return List.copyOf(shards);
    }

    @Override
    public void close() {
        finishShard();
    }

    private void ensureOpen(
        String recordDate
    ) {
        if (output != null) {
            return;
        }
        kstDate = recordDate;
        shardOrdinal = nextOrdinalByDate.getOrDefault(recordDate, 0);
        nextOrdinalByDate.put(recordDate, shardOrdinal + 1);
        temporary = shardsDirectory.resolve("." + UUID.randomUUID() + ".part");
        uncompressedDigest = newDigest();
        try {
            SecureFiles.writeNew(temporary, new byte[0]);
            compressor = new ProcessBuilder("zstd", "-q", "-3", "--stdout", "--no-progress")
                .redirectOutput(temporary.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
            output = new DigestOutputStream(
                new BufferedOutputStream(compressor.getOutputStream()), uncompressedDigest);
        } catch (IOException e) {
            throw new MigrationException("ZSTD_COMPRESSOR_START_FAILED", e);
        }
    }

    private void finishShard() {
        if (output == null) {
            return;
        }
        try {
            output.close();
            output = null;
            int exit = compressor.waitFor();
            if (exit != 0) {
                throw new MigrationException("ZSTD_COMPRESSOR_FAILED");
            }
            String digest = Sha256.of(temporary);
            String uncompressedSha256 = HexFormat.of().formatHex(uncompressedDigest.digest());
            String name = "dt=" + kstDate + "_" + "%03d".formatted(shardOrdinal)
                + "_sha256-" + digest + ".jsonl.zst";
            Path target = shardsDirectory.resolve(name);
            if (Files.exists(target)) {
                if (!digest.equals(Sha256.of(target))) {
                    throw new MigrationException("ARCHIVE_EXISTING_SHARD_DIGEST_MISMATCH");
                }
                Files.delete(temporary);
            } else {
                SecureFiles.movePrivate(temporary, target);
            }
            long compressedBytes = Files.size(target);
            if (compressedBytes > MAX_COMPRESSED_BYTES) {
                throw new MigrationException("ARCHIVE_SHARD_COMPRESSED_LIMIT_EXCEEDED");
            }
            shards.add(new ArchiveManifest.Shard(
                shardOrdinal,
                kstDate,
                digest,
                uncompressedSha256,
                archiveDirectory.relativize(target).toString(),
                compressedBytes,
                uncompressedBytes,
                batches,
                observations,
                collectedFrom,
                collectedThrough));
            resetCounters();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MigrationException("ZSTD_COMPRESSOR_INTERRUPTED", e);
        } catch (IOException e) {
            throw new MigrationException("ARCHIVE_SHARD_FINISH_FAILED", e);
        }
    }

    private void resetCounters() {
        temporary = null;
        compressor = null;
        uncompressedDigest = null;
        kstDate = null;
        batches = 0;
        observations = 0;
        uncompressedBytes = 0;
        collectedFrom = null;
        collectedThrough = null;
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
