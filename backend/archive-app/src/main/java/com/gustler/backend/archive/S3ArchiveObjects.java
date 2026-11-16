package com.gustler.backend.archive;

import java.time.Duration;
import java.util.Base64;
import org.apache.hc.core5.http.HttpStatus;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

public final class S3ArchiveObjects implements ArchiveObjects, AutoCloseable {
    private final S3Client client;
    private final String bucket;

    public S3ArchiveObjects(String bucket) {
        this.bucket = bucket;
        client = S3Client.builder().region(Region.AP_NORTHEAST_2)
            .httpClientBuilder(Apache5HttpClient.builder().maxConnections(1).connectionTimeout(Duration.ofSeconds(5))
                .socketTimeout(Duration.ofSeconds(20)))
            .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(45))).build();
    }

    @Override
    public void putVerified(String key, byte[] bytes) {
        try {
            client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).ifNoneMatch("*")
                    .checksumSHA256(Base64.getEncoder().encodeToString(ArchiveFiles.sha256(bytes))).build(),
                RequestBody.fromBytes(bytes));
        } catch (S3Exception conflict) {
            if (conflict.statusCode() != HttpStatus.SC_PRECONDITION_FAILED) {
                throw conflict;
            }
        }
    }

    @Override
    public byte[] read(String key, final int limit) {
        if (limit < 1 || limit > ArchiveFiles.MAX_BYTES) {
            throw new IllegalArgumentException("파일 읽기 한도");
        }
        return client.getObject(
            GetObjectRequest.builder().bucket(bucket).key(key).range("bytes=0-" + limit).build(),
            (response, stream) -> {
                try {
                    byte[] bytes = stream.readNBytes(limit + 1);
                    if (bytes.length > limit) {
                        throw new IllegalStateException("파일 크기 한도 초과");
                    }
                    return bytes;
                } catch (Exception failure) {
                    stream.abort();
                    throw failure;
                }
            });
    }

    @Override
    public void release(String prefix) {
    }

    @Override
    public void close() {
        client.close();
    }
}
