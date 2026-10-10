package com.gustler.backend.forecasting.infrastructure.statistics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** 지정한 비교 묶음만 다시 받는다. 목록 전체 조회나 DB 원본 삭제는 수행하지 않는다. */
final class StatisticsExtractDownloader {
    private final StatisticsExtractUploader.Command command;

    StatisticsExtractDownloader() {
        this(StatisticsExtractUploader::execute);
    }

    StatisticsExtractDownloader(StatisticsExtractUploader.Command command) {
        this.command = command;
    }

    StatisticsExtractWriter.Bundle download(Path parent, UUID exportId,
        StatisticsExtractWriter.Manifest expected) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("DB 트랜잭션 안에서는 S3 자료를 읽지 않는다");
        }
        if (parent == null || !Files.isDirectory(parent) || Files.isSymbolicLink(parent)
            || exportId == null || expected == null || expected.scope() == null
            || !"statistics-extract-v1".equals(expected.format()) || expected.scope().rowCount() < 1
            || expected.files() == null || expected.files().size() != 1
            || expected.files().getFirst() == null
            || !"rows.jsonl".equals(expected.files().getFirst().name())
            || expected.files().getFirst().byteCount() < 1
            || expected.files().getFirst().byteCount() > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("자료가 있는 16MiB 이하 비교 묶음과 작업 디렉터리가 필요하다");
        }
        String prefix = "salmonbus-be/statistics-input/v1/route-version=" + expected.scope().routeVersionId()
            + "/export-id=" + exportId + "/";
        try {
            Path directory = Files.createTempDirectory(parent, "statistics-download-");
            Path manifest = directory.resolve("manifest.json");
            get(prefix + "manifest.json", manifest, 1024 * 1024);
            if (!Files.readString(manifest).equals(JsonMapper.builder().build().writeValueAsString(expected))) {
                throw new IllegalArgumentException("S3 파일 목록이 추출한 묶음과 다르다");
            }
            var file = expected.files().getFirst();
            get(prefix + file.name(), directory.resolve(file.name()), file.byteCount());
            StatisticsInputFiles.verify(directory, expected.files());
            try (var rows = new StatisticsInputReader(directory, expected.files(), 64 * 1024)) {
                StatisticsInputRows.verify(expected.scope(), rows);
            }
            return new StatisticsExtractWriter.Bundle(directory, expected);
        } catch (IOException exception) {
            throw new IllegalStateException("S3 비교 묶음을 다시 읽지 못했다", exception);
        }
    }

    private void get(String key, Path target, long maxBytes) throws IOException {
        // 끝 위치도 포함하므로 한 바이트 초과까지 받아 과대 객체를 거부한다.
        command.run(List.of("aws", "s3api", "get-object", "--bucket", "techcourse-project-2026",
            "--key", key, "--range", "bytes=0-" + maxBytes,
            "--region", "ap-northeast-2", "--cli-connect-timeout", "5", "--cli-read-timeout", "15",
            "--no-cli-pager", "--no-cli-auto-prompt", target.toAbsolutePath().toString()));
        if (Files.isSymbolicLink(target) || !Files.isRegularFile(target) || Files.size(target) > maxBytes) {
            throw new IllegalArgumentException("S3 입력 파일의 형태나 크기가 올바르지 않다");
        }
    }
}
