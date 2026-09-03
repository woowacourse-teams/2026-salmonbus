package com.gustler.backend.migration.source;

import com.gustler.backend.migration.MigrationException;
import com.gustler.backend.migration.Sha256;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.sts.StsClient;

/** 개인 측 default credential로 List/Get만 노출한다. Put/Delete API는 이 형에 없다. */
public final class AwsSourceObjectStore implements SourceObjectStore {

    private final S3Client s3;
    private final StsClient sts;

    public AwsSourceObjectStore(
        String region
    ) {
        DefaultCredentialsProvider credentials = DefaultCredentialsProvider.builder().build();
        UrlConnectionHttpClient.Builder http = UrlConnectionHttpClient.builder();
        this.s3 = S3Client.builder()
            .region(Region.of(region))
            .credentialsProvider(credentials)
            .httpClientBuilder(http)
            .build();
        this.sts = StsClient.builder()
            .region(Region.of(region))
            .credentialsProvider(credentials)
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .build();
    }

    @Override
    public String callerAccountId() {
        try {
            return sts.getCallerIdentity().account();
        } catch (SdkException e) {
            throw new MigrationException("SOURCE_CALLER_IDENTITY_FAILED", e);
        }
    }

    @Override
    public List<ObjectInfo> list(
        String bucket,
        String prefix,
        Instant cutoffAt
    ) {
        List<ObjectInfo> objects = new ArrayList<>();
        String continuation = null;
        do {
            ListObjectsV2Request.Builder request = ListObjectsV2Request.builder()
                .bucket(bucket)
                .prefix(prefix);
            if (continuation != null) {
                request.continuationToken(continuation);
            }
            ListObjectsV2Response response;
            try {
                response = s3.listObjectsV2(request.build());
            } catch (SdkException e) {
                throw new MigrationException("SOURCE_S3_LIST_DENIED_OR_FAILED", e);
            }
            response.contents().forEach(object -> {
                if (object.key().endsWith("/") || object.lastModified().isAfter(cutoffAt)) {
                    return;
                }
                objects.add(new ObjectInfo(
                    kindOf(object.key()),
                    object.key(),
                    normalizedEtag(object.eTag()),
                    object.size(),
                    object.lastModified()));
            });
            if (!response.isTruncated()) {
                continuation = null;
            } else {
                String next = response.nextContinuationToken();
                if (next == null || next.isBlank() || next.equals(continuation)) {
                    throw new MigrationException("SOURCE_S3_PAGINATION_INVALID");
                }
                continuation = next;
            }
        } while (continuation != null);
        return List.copyOf(objects);
    }

    @Override
    public LoadedObject get(
        String bucket,
        ObjectInfo expected
    ) {
        GetObjectRequest request = GetObjectRequest.builder()
            .bucket(bucket)
            .key(expected.key())
            .ifMatch("\"" + expected.etag() + "\"")
            .build();
        try (ResponseInputStream<GetObjectResponse> response = s3.getObject(request)) {
            byte[] body = response.readAllBytes();
            GetObjectResponse metadata = response.response();
            if (body.length != expected.size()
                || metadata.contentLength() != expected.size()
                || !normalizedEtag(metadata.eTag()).equals(expected.etag())) {
                throw new MigrationException("SOURCE_OBJECT_CHANGED_AFTER_INVENTORY");
            }
            boolean kms = metadata.serverSideEncryption() == ServerSideEncryption.AWS_KMS
                || metadata.serverSideEncryption() == ServerSideEncryption.AWS_KMS_DSSE;
            String keyDigest = kms && metadata.ssekmsKeyId() != null
                ? Sha256.of(metadata.ssekmsKeyId()) : null;
            return new LoadedObject(expected, body, Map.copyOf(metadata.metadata()), kms, keyDigest);
        } catch (software.amazon.awssdk.services.s3.model.S3Exception e) {
            if (e.statusCode() == 403) {
                throw new MigrationException("SOURCE_S3_GET_OR_KMS_DECRYPT_DENIED", e);
            }
            throw new MigrationException("SOURCE_S3_GET_FAILED", e);
        } catch (IOException | SdkException e) {
            throw new MigrationException("SOURCE_S3_GET_FAILED", e);
        }
    }

    @Override
    public void close() {
        s3.close();
        sts.close();
    }

    private static Kind kindOf(
        String key
    ) {
        if (key.startsWith("records/")) {
            return Kind.RECORD;
        }
        if (key.startsWith("raw/")) {
            return Kind.RAW;
        }
        throw new MigrationException("SOURCE_INVENTORY_PREFIX_INVALID");
    }

    private static String normalizedEtag(
        String value
    ) {
        if (value == null || value.isBlank()) {
            throw new MigrationException("SOURCE_OBJECT_ETAG_INVALID");
        }
        return value.replace("\"", "");
    }
}
