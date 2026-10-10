package com.gustler.backend.forecasting.infrastructure.statistics;

import com.gustler.backend.forecasting.application.evaluation.EvaluationArchiveStorage;
import com.gustler.backend.forecasting.application.evaluation.EvaluationArchiveSource;
import com.gustler.backend.forecasting.application.statistics.StatisticsArchiveReader;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 팀 경로의 불변 객체만 사용한다. 작업 전용 디렉터리는 호출이 끝나면 정리한다. */
public final class EvaluationArchiveObjectStore implements EvaluationArchiveStorage, EvaluationArchiveSource, StatisticsArchiveReader {
    private static final String BUCKET = "techcourse-project-2026";
    private static final String ROOT = "salmonbus-be/evaluation-archive/v1/";
    private final Path workDirectory;
    private final long maxBytes;
    private final StatisticsExtractUploader.Command command;

    public EvaluationArchiveObjectStore(Path workDirectory, long maxBytes) {
        this(workDirectory, maxBytes, StatisticsExtractUploader::execute);
    }

    EvaluationArchiveObjectStore(Path workDirectory, long maxBytes, StatisticsExtractUploader.Command command) {
        if (workDirectory == null || !Files.isDirectory(workDirectory, LinkOption.NOFOLLOW_LINKS)
            || maxBytes < 1 || maxBytes > 16 * 1024 * 1024 || command == null) {
            throw new IllegalArgumentException("작업 디렉터리와 16MiB 이하의 파일 한도가 필요하다");
        }
        this.workDirectory = workDirectory;
        this.maxBytes = maxBytes;
        this.command = command;
    }

    @Override
    public synchronized Verification storeAndVerify(EvaluationArchiveBatch batch, List<EvaluationArchiveBatch.Row> rows) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("S3 전송 중 DB 트랜잭션을 유지하지 않는다");
        }
        var encoded = EvaluationArchiveFiles.encode(batch, rows, maxBytes);
        Path directory = null;
        RuntimeException failure = null;
        try {
            if (Files.getFileStore(workDirectory).getUsableSpace() < 4 * maxBytes + 1024 * 1024) {
                throw new IOException("작업 파일을 만들 임시 디스크 여유가 부족하다");
            }
            directory = Files.createTempDirectory(workDirectory, "evaluation-archive-");
            Path data = directory.resolve("evaluations.jsonl");
            Path manifest = directory.resolve("manifest.json");
            Files.write(data, encoded.rows());
            Files.write(manifest, encoded.manifest());
            String prefix = ROOT + "route-version=" + batch.routeVersionId() + "/batch=" + batch.id() + "/";
            ensureStored(prefix + "evaluations.jsonl", data, directory.resolve("downloaded-rows"), encoded.rows());
            // 자료가 다시 읽히는 것을 확인한 다음 설명 파일을 게시한다.
            ensureStored(prefix + "manifest.json", manifest, directory.resolve("downloaded-manifest"), encoded.manifest());
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
                    for (String name : List.of("evaluations.jsonl", "manifest.json", "downloaded-rows", "downloaded-manifest")) {
                        Files.deleteIfExists(directory.resolve(name));
                    }
                    Files.delete(directory);
                } catch (IOException exception) {
                    if (failure != null) {
                        failure.addSuppressed(exception);
                    } else {
                        throw new IllegalStateException("이관 임시 파일을 정리하지 못했다", exception);
                    }
                }
            }
        }
    }

    private void ensureStored(String key, Path source, Path downloaded, byte[] expected) throws IOException {
        RuntimeException putFailure = null;
        try {
            command.run(List.of("aws", "s3api", "put-object", "--bucket", BUCKET, "--key", key,
                "--body", source.toAbsolutePath().toString(), "--if-none-match", "*", "--checksum-algorithm", "SHA256",
                "--tagging", "Service=techcourse&Role=techcourse-etc&ProjectTeam=salmonbus", "--region", "ap-northeast-2",
                "--cli-connect-timeout", "5", "--cli-read-timeout", "15", "--no-cli-pager", "--no-cli-auto-prompt"));
        } catch (RuntimeException exception) {
            if (Thread.currentThread().isInterrupted()) {
                throw exception;
            }
            putFailure = exception;
        }
        try {
            // 같은 키가 이미 존재해도 전체 내용이 일치해야 재시도가 성공한다. 한 바이트 초과까지 확인한다.
            command.run(List.of("aws", "s3api", "get-object", "--bucket", BUCKET, "--key", key,
                "--range", "bytes=0-" + expected.length, "--region", "ap-northeast-2",
                "--cli-connect-timeout", "5", "--cli-read-timeout", "15", "--no-cli-pager", "--no-cli-auto-prompt",
                downloaded.toAbsolutePath().toString()));
            if (Files.isSymbolicLink(downloaded) || Files.size(downloaded) != expected.length
                || !Arrays.equals(Files.readAllBytes(downloaded), expected)) {
                throw new IllegalStateException("S3에서 다시 읽은 원본이 예약 자료와 다르다");
            }
        } catch (IOException | RuntimeException exception) {
            if (putFailure != null) {
                exception.addSuppressed(putFailure);
            }
            throw exception;
        }
    }

    @Override
    public synchronized List<StatisticsArchiveReader.Row> read(Reference reference) {
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
            String prefix = ROOT + "route-version=" + reference.routeVersionId() + "/batch=" + reference.batchId() + "/";
            byte[] manifest = downloadBounded(prefix + "manifest.json", directory.resolve("manifest.json"), 16384);
            var metadata = EvaluationArchiveFiles.manifest(reference, manifest, maxBytes);
            byte[] data = downloadBounded(prefix + "evaluations.jsonl", directory.resolve("evaluations.jsonl"), metadata.bytes());
            return EvaluationArchiveFiles.decode(reference, manifest, data, maxBytes).stream()
                .map(row -> new StatisticsArchiveReader.Row(
                    new StatisticsArchiveReader.Key(row.key().observationId(), row.key().stopOrder()),
                    row.originalJson(), row.sha256())).toList();
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
                    if (failure != null) failure.addSuppressed(exception);
                    else throw new IllegalStateException("읽기 임시 파일을 정리하지 못했다", exception);
                }
            }
        }
    }

    @Override
    public List<EvaluationArchiveBatch.Row> readOriginal(EvaluationArchiveBatch verified) {
        return read(new Reference(verified.id(), verified.routeVersionId(), verified.qualityRevision(),
            verified.rowCount(), verified.manifestSha256())).stream()
            .map(row -> new EvaluationArchiveBatch.Row(
                new EvaluationArchiveBatch.Key(row.key().observationId(), row.key().stopOrder()),
                row.originalJson(), row.sha256())).toList();
    }

    private byte[] downloadBounded(String key, Path path, long limit) throws IOException {
        command.run(List.of("aws", "s3api", "get-object", "--bucket", BUCKET, "--key", key,
            "--range", "bytes=0-" + limit, "--region", "ap-northeast-2", "--cli-connect-timeout", "5",
            "--cli-read-timeout", "15", "--no-cli-pager", "--no-cli-auto-prompt", path.toAbsolutePath().toString()));
        if (Files.isSymbolicLink(path) || Files.size(path) > limit) {
            throw new IllegalArgumentException("이관 파일의 크기 한도를 넘었다");
        }
        return Files.readAllBytes(path);
    }
}
