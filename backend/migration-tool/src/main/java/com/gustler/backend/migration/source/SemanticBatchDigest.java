package com.gustler.backend.migration.source;

import com.gustler.backend.migration.Sha256;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/** vehicle identity와 무관한 versioned length-prefix batch identity. */
final class SemanticBatchDigest {

    private static final String VERSION = "salmonbus-s3-semantic-batch-v1";

    private SemanticBatchDigest() {
    }

    static String of(
        String sourceAccount,
        String sourceRouteId,
        Instant requestedAt,
        Instant responseReceivedAt,
        String rawBodySha256,
        int roundIndex
    ) {
        Sha256.requireDigest(rawBodySha256, "SOURCE_SEMANTIC_RAW_DIGEST_INVALID");
        MessageDigest digest = newDigest();
        update(digest, VERSION);
        update(digest, sourceAccount);
        update(digest, sourceRouteId);
        update(digest, requestedAt.toString());
        update(digest, responseReceivedAt.toString());
        update(digest, rawBodySha256);
        update(digest, Integer.toString(roundIndex));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void update(
        MessageDigest digest,
        String value
    ) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
