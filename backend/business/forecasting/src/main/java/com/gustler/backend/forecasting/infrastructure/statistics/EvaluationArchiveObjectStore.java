package com.gustler.backend.forecasting.infrastructure.statistics;

import com.gustler.backend.forecasting.api.evaluation.EvaluationArchiveObjects;
import com.gustler.backend.forecasting.application.evaluation.EvaluationArchiveSource;
import com.gustler.backend.forecasting.application.evaluation.EvaluationArchiveStorage;
import com.gustler.backend.forecasting.application.evaluation.EvaluationArchiveStorage.Verification;
import com.gustler.backend.forecasting.application.statistics.StatisticsArchiveReader;
import com.gustler.backend.forecasting.application.statistics.StatisticsArchiveReader.Key;
import com.gustler.backend.forecasting.application.statistics.StatisticsArchiveReader.Reference;
import com.gustler.backend.forecasting.application.statistics.StatisticsArchiveReader.Row;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch;
import com.gustler.backend.forecasting.infrastructure.statistics.EvaluationArchiveFiles.Encoded;
import com.gustler.backend.forecasting.infrastructure.statistics.EvaluationArchiveFiles.Manifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 팀 경로의 불변 객체만 사용한다. 작업 전용 디렉터리는 호출이 끝나면 정리한다. */
public final class EvaluationArchiveObjectStore implements EvaluationArchiveStorage, EvaluationArchiveSource,
    StatisticsArchiveReader {
    private static final String BUCKET = "techcourse-project-2026";
    private static final String ROOT = "salmonbus-be/evaluation-archive/v1/";
    private final Path workDirectory;
    private final long maxBytes;
    private final EvaluationArchiveObjects objects;

    public EvaluationArchiveObjectStore(Path workDirectory, final long maxBytes, EvaluationArchiveObjects objects) {
        if (!hasValidConfiguration(workDirectory, maxBytes, objects)) {
            throw new IllegalArgumentException("작업 디렉터리와 16MiB 이하의 파일 한도가 필요하다");
        }
        this.workDirectory = workDirectory;
        this.maxBytes = maxBytes;
        this.objects = objects;
    }

    private boolean hasValidConfiguration(Path workDirectory, long maxBytes, EvaluationArchiveObjects objects) {
        if (workDirectory == null || objects == null) {
            return false;
        }
        if (!Files.isDirectory(workDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        return maxBytes >= 1 && maxBytes <= 16 * 1024 * 1024;
    }

    @Override
    public synchronized Verification storeAndVerify(
        EvaluationArchiveBatch batch,
        List<EvaluationArchiveBatch.Row> rows
    ) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("S3 전송 중 DB 트랜잭션을 유지하지 않는다");
        }
        Encoded encoded = EvaluationArchiveFiles.encode(batch, rows, maxBytes);
        Path directory = null;
        RuntimeException failure = null;
        try {
            if (Files.getFileStore(workDirectory).getUsableSpace() < 4 * maxBytes + 1024 * 1024) {
                throw new IOException("작업 파일을 만들 임시 디스크 여유가 부족하다");
            }
            directory = Files.createTempDirectory(workDirectory, "evaluation-archive-");
            Path rowsFile = directory.resolve("evaluations.jsonl");
            Path manifest = directory.resolve("manifest.json");
            Files.write(rowsFile, encoded.rows());
            Files.write(manifest, encoded.manifest());
            String prefix = ROOT + "route-version=" + batch.routeVersionId() + "/batch=" + batch.id() + "/";
            ensureStored(prefix + "evaluations.jsonl", rowsFile, directory.resolve("downloaded-rows"), encoded.rows());
            // 원본 저장이 확인된 다음 설명 파일을 게시한다.
            ensureStored(prefix + "manifest.json", manifest, directory.resolve("downloaded-manifest"),
                encoded.manifest());
            return new Verification("s3://" + BUCKET + "/" + prefix, EvaluationArchiveFiles.digest(encoded.manifest()));
        } catch (IOException exception) {
            failure = new IllegalStateException("이관 작업 파일을 처리하지 못했다", exception);
            throw failure;
        } catch (RuntimeException exception) {
            failure = exception;
            throw exception;
        } finally {
            if (directory != null) {
                try {
                    // 이번 호출이 생성한 이름만 정리한다. 재귀 삭제나 사용자 경로 삭제는 하지 않는다.
                    for (
                        String name : List.of("evaluations.jsonl", "manifest.json", "downloaded-rows",
                        "downloaded-manifest")) {
                        Files.deleteIfExists(directory.resolve(name));
                    }
                    Files.delete(directory);
                } catch (IOException exception) {
                    if (failure == null) {
                        throw new IllegalStateException("이관 임시 파일을 정리하지 못했다", exception);
                    }
                    failure.addSuppressed(exception);
                }
            }
        }
    }

    private void ensureStored(
        String key,
        Path source,
        Path downloaded,
        byte[] expected
    ) throws IOException {
        try {
            objects.putIfAbsent(BUCKET, key, source, EvaluationArchiveFiles.checksumSha256(expected));
            return;
        } catch (RuntimeException exception) {
            if (Thread.currentThread().isInterrupted()) {
                throw exception;
            }
            try {
                // 충돌·응답 유실로 저장 결과가 불확실하면 한 바이트 초과까지 읽어 전체를 비교한다.
                objects.download(BUCKET, key, downloaded, expected.length);
                if (!matchesExpectedContent(downloaded, expected)) {
                    throw new IllegalStateException("S3에서 다시 읽은 원본이 예약 자료와 다르다");
                }
            } catch (IOException | RuntimeException readFailure) {
                readFailure.addSuppressed(exception);
                throw readFailure;
            }
        }
    }

    private boolean matchesExpectedContent(Path downloaded, byte[] expected) throws IOException {
        if (Files.isSymbolicLink(downloaded) || Files.size(downloaded) != expected.length) {
            return false;
        }
        return Arrays.equals(Files.readAllBytes(downloaded), expected);
    }

    @Override
    public List<EvaluationArchiveBatch.Row> readOriginal(EvaluationArchiveBatch verified) {
        return read(new Reference(verified.id(), verified.routeVersionId(), verified.qualityRevision(),
            verified.rowCount(), verified.manifestSha256())).stream()
            .map(row -> new EvaluationArchiveBatch.Row(
                new EvaluationArchiveBatch.Key(row.key().observationId(), row.key().stopOrder()),
                row.originalJson(), row.sha256()))
            .toList();
    }

    @Override
    public synchronized List<Row> read(Reference reference) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("이관 파일은 DB 트랜잭션 밖에서 읽어야 한다");
        }
        Path directory = null;
        RuntimeException failure = null;
        try {
            if (Files.getFileStore(workDirectory).getUsableSpace() < 2 * maxBytes + 1024 * 1024) {
                throw new IOException("이관 파일을 읽을 디스크 여유가 부족하다");
            }
            directory = Files.createTempDirectory(workDirectory, "evaluation-read-");
            String prefix = ROOT + "route-version=" + reference.routeVersionId() + "/batch=" + reference.batchId()
                + "/";
            byte[] manifest = downloadBounded(prefix + "manifest.json", directory.resolve("manifest.json"), 16384);
            Manifest metadata = EvaluationArchiveFiles.manifest(reference, manifest, maxBytes);
            byte[] rowsBytes =
                downloadBounded(prefix + "evaluations.jsonl", directory.resolve("evaluations.jsonl"), metadata.bytes());
            return EvaluationArchiveFiles.decode(reference, manifest, rowsBytes, maxBytes)
                .stream()
                .map(row -> new Row(
                    new Key(row.key().observationId(), row.key().stopOrder()),
                    row.originalJson(), row.sha256()))
                .toList();
        } catch (IOException exception) {
            failure = new IllegalStateException("이관 원본을 다시 읽지 못했다", exception);
            throw failure;
        } catch (RuntimeException exception) {
            failure = exception;
            throw exception;
        } finally {
            if (directory != null) {
                try {
                    Files.deleteIfExists(directory.resolve("manifest.json"));
                    Files.deleteIfExists(directory.resolve("evaluations.jsonl"));
                    Files.delete(directory);
                } catch (IOException exception) {
                    if (failure == null) {
                        throw new IllegalStateException("읽기 임시 파일을 정리하지 못했다", exception);
                    }
                    failure.addSuppressed(exception);
                }
            }
        }
    }

    /**
     * 설정한 바이트 한도 안에서 아카이브를 내려받는다. 한도 초과는 불완전 파일을 정상 입력으로 반환하지 않고 실패로 전파한다.
     */
    private byte[] downloadBounded(String key, Path path, final long limit) throws IOException {
        objects.download(BUCKET, key, path, limit);
        if (Files.isSymbolicLink(path) || Files.size(path) > limit) {
            throw new IllegalArgumentException("이관 파일의 크기 한도를 넘었다");
        }
        return Files.readAllBytes(path);
    }
}
