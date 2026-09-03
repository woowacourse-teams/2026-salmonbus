package com.gustler.backend.migration.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.migration.MigrationException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class SemanticBatchDigestTest {

    private static final String VERSION = "salmonbus-s3-semantic-batch-v1";
    private static final String ACCOUNT = "827325854159";
    private static final String ROUTE = "204000057";
    private static final Instant REQUESTED = Instant.parse("2026-08-14T08:00:00.100Z");
    private static final Instant RECEIVED = Instant.parse("2026-08-14T08:00:00.300Z");
    private static final String RAW = "a".repeat(64);

    @Test
    void sameInputsAlwaysProduceTheSameDigestRegardlessOfHowTheInstantWasWritten() {
        String first = SemanticBatchDigest.of(ACCOUNT, ROUTE, REQUESTED, RECEIVED, RAW, 0);
        String second = SemanticBatchDigest.of(ACCOUNT, ROUTE, REQUESTED, RECEIVED, RAW, 0);

        assertThat(first).isEqualTo(second).matches("[0-9a-f]{64}");
        assertThat(SemanticBatchDigest.of(
            ACCOUNT, ROUTE,
            Instant.parse("2026-08-14T08:00:00Z"), Instant.parse("2026-08-14T08:00:01Z"), RAW, 0))
            .isEqualTo(SemanticBatchDigest.of(
                ACCOUNT, ROUTE,
                Instant.parse("2026-08-14T08:00:00.000000000Z"),
                Instant.parse("2026-08-14T08:00:01.000Z"), RAW, 0));
    }

    @Test
    void everyFieldIsBoundToItsPositionSoSwappedOrChangedValuesDiverge() {
        String base = SemanticBatchDigest.of(ACCOUNT, ROUTE, REQUESTED, RECEIVED, RAW, 0);

        assertThat(SemanticBatchDigest.of(ROUTE, ACCOUNT, REQUESTED, RECEIVED, RAW, 0))
            .isNotEqualTo(base);
        assertThat(SemanticBatchDigest.of(ACCOUNT, ROUTE, RECEIVED, RECEIVED, RAW, 0))
            .isNotEqualTo(base);
        assertThat(SemanticBatchDigest.of(ACCOUNT, ROUTE, REQUESTED, REQUESTED, RAW, 0))
            .isNotEqualTo(base);
        assertThat(SemanticBatchDigest.of(ACCOUNT, ROUTE, REQUESTED, RECEIVED, "b".repeat(64), 0))
            .isNotEqualTo(base);
        assertThat(SemanticBatchDigest.of(ACCOUNT, ROUTE, REQUESTED, RECEIVED, RAW, 1))
            .isNotEqualTo(base);
        assertThat(SemanticBatchDigest.of(ACCOUNT, ROUTE, REQUESTED, RECEIVED, RAW, -1))
            .isNotEqualTo(base);
    }

    @Test
    void lengthPrefixedFramingKeepsMovedFieldBoundariesFromCollapsingIntoTheSameDigest() {
        assertThat(SemanticBatchDigest.of("ab", "c", REQUESTED, RECEIVED, RAW, 0))
            .isNotEqualTo(SemanticBatchDigest.of("a", "bc", REQUESTED, RECEIVED, RAW, 0));
        assertThat(SemanticBatchDigest.of(ACCOUNT, ROUTE, REQUESTED, RECEIVED, RAW, 0))
            .isNotEqualTo(SemanticBatchDigest.of(
                ACCOUNT + ROUTE.charAt(0), ROUTE.substring(1), REQUESTED, RECEIVED, RAW, 0));
        assertThat(SemanticBatchDigest.of("", "abc", REQUESTED, RECEIVED, RAW, 0))
            .isNotEqualTo(SemanticBatchDigest.of("abc", "", REQUESTED, RECEIVED, RAW, 0));
    }

    @Test
    void versionIsTheFirstFramedFieldSoAFutureRuleGetsItsOwnDigestDomain() {
        String actual = SemanticBatchDigest.of(ACCOUNT, ROUTE, REQUESTED, RECEIVED, RAW, 0);

        assertThat(actual).isEqualTo(framed(
            VERSION, ACCOUNT, ROUTE, REQUESTED.toString(), RECEIVED.toString(), RAW, "0"));
        assertThat(actual).isNotEqualTo(framed(
            ACCOUNT, ROUTE, REQUESTED.toString(), RECEIVED.toString(), RAW, "0"));
        assertThat(actual).isNotEqualTo(framed(
            VERSION + "-v2", ACCOUNT, ROUTE, REQUESTED.toString(), RECEIVED.toString(), RAW, "0"));
    }

    @Test
    void rejectsRawBodyDigestsThatAreNotLowercaseSixtyFourHex() {
        assertThat(rawDigestFailure(null)).isEqualTo("SOURCE_SEMANTIC_RAW_DIGEST_INVALID");
        assertThat(rawDigestFailure("")).isEqualTo("SOURCE_SEMANTIC_RAW_DIGEST_INVALID");
        assertThat(rawDigestFailure("a".repeat(63))).isEqualTo("SOURCE_SEMANTIC_RAW_DIGEST_INVALID");
        assertThat(rawDigestFailure("a".repeat(65))).isEqualTo("SOURCE_SEMANTIC_RAW_DIGEST_INVALID");
        assertThat(rawDigestFailure("A".repeat(64))).isEqualTo("SOURCE_SEMANTIC_RAW_DIGEST_INVALID");
        assertThat(rawDigestFailure("g".repeat(64))).isEqualTo("SOURCE_SEMANTIC_RAW_DIGEST_INVALID");
    }

    @Test
    void emptyTextIsFramedAsAValueAndNullTextIsNotDefendedAgainst() {
        assertThat(SemanticBatchDigest.of("", "", REQUESTED, RECEIVED, RAW, 0))
            .isNotEqualTo(SemanticBatchDigest.of(" ", "", REQUESTED, RECEIVED, RAW, 0))
            .isNotEqualTo(SemanticBatchDigest.of("", " ", REQUESTED, RECEIVED, RAW, 0))
            .matches("[0-9a-f]{64}");

        assertThatThrownBy(() -> SemanticBatchDigest.of(null, ROUTE, REQUESTED, RECEIVED, RAW, 0))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SemanticBatchDigest.of(ACCOUNT, null, REQUESTED, RECEIVED, RAW, 0))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SemanticBatchDigest.of(ACCOUNT, ROUTE, null, RECEIVED, RAW, 0))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SemanticBatchDigest.of(ACCOUNT, ROUTE, REQUESTED, null, RAW, 0))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void backfillAttemptKeyCannotCollideWithACollectorAttemptKey() {
        String attemptKey = "s3v1:" + SemanticBatchDigest.of(ACCOUNT, ROUTE, REQUESTED, RECEIVED, RAW, 0);
        String liveAttemptKey = "%s-%s".formatted(ROUTE, Instant.parse("2026-09-02T10:27:51Z"));

        assertThat(attemptKey).matches("s3v1:[0-9a-f]{64}").hasSize(69);
        assertThat(liveAttemptKey).doesNotStartWith("s3v1:").isNotEqualTo(attemptKey);
        assertThat(attemptKey.length()).isLessThanOrEqualTo(160);
    }

    private static String rawDigestFailure(
        String rawBodySha256
    ) {
        try {
            SemanticBatchDigest.of(ACCOUNT, ROUTE, REQUESTED, RECEIVED, rawBodySha256, 0);
        } catch (MigrationException e) {
            return e.code();
        }
        return null;
    }

    private static String framed(
        String... values
    ) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        for (String value : values) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
