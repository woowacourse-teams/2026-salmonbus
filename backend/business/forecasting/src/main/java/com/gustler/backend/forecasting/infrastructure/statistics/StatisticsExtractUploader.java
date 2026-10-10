package com.gustler.backend.forecasting.infrastructure.statistics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** 소량 비교 묶음 업로드. EC2에 설치된 AWS CLI v2를 사용하며 운영 자동 실행에는 아직 연결하지 않는다. */
final class StatisticsExtractUploader {
    private static final String BUCKET = "techcourse-project-2026";
    private static final String ROOT = "salmonbus-be/statistics-input/v1/";
    private final Command command;

    StatisticsExtractUploader() { this(StatisticsExtractUploader::execute); }
    StatisticsExtractUploader(Command command) { this.command = command; }

    String upload(StatisticsExtractWriter.Bundle bundle, UUID exportId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("DB 트랜잭션 안에서는 S3 전송을 시작하지 않는다");
        }
        if (bundle == null || exportId == null) {
            throw new IllegalArgumentException("검증된 묶음과 추출 번호가 필요하다");
        }
        StatisticsInputFiles.verify(bundle.directory(), bundle.manifest().files());
        Path manifestPath = bundle.directory().resolve("manifest.json");
        if (Files.isSymbolicLink(manifestPath) || !Files.isRegularFile(manifestPath)) {
            throw new IllegalArgumentException("완료된 파일 설명서가 없다");
        }
        try {
            if (Files.size(manifestPath) > 1024 * 1024
                || !Files.readString(manifestPath).equals(JsonMapper.builder().build().writeValueAsString(bundle.manifest()))) {
                throw new IllegalArgumentException("파일 설명서가 검증된 내용과 다르다");
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("파일 설명서를 읽지 못했다", exception);
        }
        String prefix = ROOT + "route-version=" + bundle.manifest().scope().routeVersionId()
            + "/export-id=" + exportId + "/";
        for (var file : bundle.manifest().files()) {
            put(prefix + file.name(), bundle.directory().resolve(file.name()));
        }
        // 모든 자료 파일이 성공한 경우에만 묶음 설명서를 게시한다.
        put(prefix + "manifest.json", manifestPath);
        return "s3://" + BUCKET + "/" + prefix;
    }

    private void put(String key, Path file) {
        command.run(List.of("aws", "s3api", "put-object", "--bucket", BUCKET, "--key", key,
            "--body", file.toAbsolutePath().toString(), "--if-none-match", "*",
            "--checksum-algorithm", "SHA256", "--tagging",
            "Service=techcourse&Role=techcourse-etc&ProjectTeam=salmonbus",
            "--region", "ap-northeast-2", "--cli-connect-timeout", "5", "--cli-read-timeout", "15",
            "--no-cli-pager", "--no-cli-auto-prompt"));
    }

    static void execute(List<String> arguments) {
        Process process = null;
        try {
            var builder = new ProcessBuilder(new ArrayList<>(arguments));
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().put("AWS_MAX_ATTEMPTS", "2");
            process = builder.start();
            if (!process.waitFor(45, TimeUnit.SECONDS)) {
                throw new IllegalStateException("S3 업로드 제한 시간을 초과했다");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("S3 업로드에 실패했다. AWS CLI 종료 코드=" + process.exitValue());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("S3 업로드가 중단됐다", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("AWS CLI를 실행하지 못했다", exception);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    @FunctionalInterface
    interface Command { void run(List<String> arguments); }
}
