package com.gustler.backend.worker.archive;

import com.gustler.backend.forecasting.api.evaluation.EvaluationArchiveObjects;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Objects;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** 프로세스와 연결을 매 요청마다 만들지 않고 하나의 S3 클라이언트를 재사용한다. */
public final class S3EvaluationArchiveObjects implements EvaluationArchiveObjects, AutoCloseable {
    private static final long MAX_BYTES = 16 * 1024 * 1024;
    private static final String TAGS = "Service=techcourse&Role=techcourse-etc&ProjectTeam=salmonbus";
    private final S3Client client;

    public S3EvaluationArchiveObjects(S3Client client) {
        this.client = Objects.requireNonNull(client);
    }

    public static S3EvaluationArchiveObjects create() {
        return new S3EvaluationArchiveObjects(clientBuilder().build());
    }

    static S3ClientBuilder clientBuilder() {
        return S3Client.builder()
            .region(Region.AP_NORTHEAST_2)
            .httpClientBuilder(Apache5HttpClient.builder()
                .maxConnections(2)
                .connectionAcquisitionTimeout(Duration.ofSeconds(5))
                .connectionTimeout(Duration.ofSeconds(5))
                .socketTimeout(Duration.ofSeconds(15)))
            .serviceConfiguration(configuration -> configuration.chunkedEncodingEnabled(false))
            .overrideConfiguration(configuration -> configuration
                .apiCallTimeout(Duration.ofSeconds(45))
                .apiCallAttemptTimeout(Duration.ofSeconds(20))
                .retryStrategy(AwsRetryStrategy.standardRetryStrategy().toBuilder().maxAttempts(2).build()));
    }

    @Override
    public void putIfAbsent(String bucket, String key, Path source, String checksumSha256) {
        if (checksumSha256 == null || !checksumSha256.matches("[A-Za-z0-9+/]{43}=")) {
            throw new IllegalArgumentException("인코딩한 원본의 Base64 SHA-256이 필요하다");
        }
        PutObjectRequest request = PutObjectRequest.builder()
            .bucket(bucket).key(key).ifNoneMatch("*")
            .checksumAlgorithm(ChecksumAlgorithm.SHA256)
            .checksumSHA256(checksumSha256)
            .tagging(TAGS).build();
        client.putObject(request, RequestBody.fromFile(source));
    }

    @Override
    public void download(String bucket, String key, Path destination, long maxBytes) {
        if (maxBytes < 1 || maxBytes > MAX_BYTES) {
            throw new IllegalArgumentException("이관 읽기 한도는 1바이트~16MiB여야 한다");
        }
        GetObjectRequest request = GetObjectRequest.builder()
            .bucket(bucket).key(key).range("bytes=0-" + maxBytes).build();
        // 관리되는 transformer 안에서 읽어 API 시간 제한을 응답 본문에도 적용한다.
        byte[] bytes = client.getObject(request, (response, stream) -> {
            try {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("이관 읽기가 중단됐다");
                }
                if (response.contentLength() != null && response.contentLength() > maxBytes) {
                    throw new IllegalArgumentException("이관 파일의 크기 한도를 넘었다");
                }
                byte[] received = stream.readNBytes(Math.toIntExact(maxBytes + 1));
                if (received.length > maxBytes) {
                    throw new IllegalArgumentException("이관 파일의 크기 한도를 넘었다");
                }
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("이관 읽기가 중단됐다");
                }
                return received;
            } catch (Exception exception) {
                // Range를 무시하는 응답도 나머지를 배수하지 않고 즉시 끊는다.
                stream.abort();
                throw exception;
            }
        });
        try {
            Files.write(destination, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException exception) {
            throw new IllegalStateException("이관 다운로드 파일을 쓰지 못했다", exception);
        }
    }

    @Override
    public void close() {
        client.close();
    }
}
