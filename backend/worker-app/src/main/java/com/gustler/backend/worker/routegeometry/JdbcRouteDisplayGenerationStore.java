package com.gustler.backend.worker.routegeometry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Durable work ownership. All lease comparisons use the database clock. */
@Repository
public class JdbcRouteDisplayGenerationStore {
    private final JdbcClient jdbc;
    private final int attemptLimit;

    public JdbcRouteDisplayGenerationStore(JdbcClient jdbc) {
        this(jdbc, 5);
    }

    @Autowired
    public JdbcRouteDisplayGenerationStore(JdbcClient jdbc,
            @Value("${route-geometry.generation.max-attempts:5}") int attemptLimit) {
        if (attemptLimit < 1) throw new IllegalArgumentException("Attempt limit must be positive");
        this.jdbc = jdbc;
        this.attemptLimit = attemptLimit;
    }

    public record Job(String generationKey, String routeId, String inputDigest,
                      String evidenceRevision, String algorithmRevision, String inputJson,
                      UUID token, int attempts, long ageAtClaimMillis, String sourceManifestId, java.time.Instant createdAt) {}

    public void enqueue(String key, String routeId, String inputDigest,
                        String evidenceRevision, String algorithmRevision, String inputJson) {
        enqueue(key,routeId,inputDigest,evidenceRevision,algorithmRevision,inputJson,null);
    }
    public void enqueue(String key,String routeId,String inputDigest,String evidenceRevision,
            String algorithmRevision,String inputJson,String sourceManifestId) {
        jdbc.sql("""
            INSERT INTO route_display_generation_job
                (generation_key,route_id,input_digest,evidence_revision,algorithm_revision,input_json,source_manifest_id,attempt_limit)
            VALUES (?,?,?,?,?,CAST(? AS jsonb),?,?) ON CONFLICT (generation_key) DO UPDATE
            SET status=CASE WHEN route_display_generation_job.attempts < route_display_generation_job.attempt_limit
                    THEN 'PENDING' ELSE 'FAILED' END,
                next_attempt_at=clock_timestamp(),updated_at=clock_timestamp(),
                failed_at=CASE WHEN route_display_generation_job.attempts >= route_display_generation_job.attempt_limit
                    THEN clock_timestamp() ELSE NULL END,
                last_failure_code=CASE WHEN route_display_generation_job.attempts >= route_display_generation_job.attempt_limit
                    THEN 'ATTEMPTS_EXHAUSTED' ELSE route_display_generation_job.last_failure_code END,
                last_failure_at=CASE WHEN route_display_generation_job.attempts >= route_display_generation_job.attempt_limit
                    THEN clock_timestamp() ELSE route_display_generation_job.last_failure_at END
            WHERE route_display_generation_job.status='SUPERSEDED'
            """).params(key, routeId, inputDigest, evidenceRevision, algorithmRevision, inputJson,sourceManifestId,attemptLimit).update();
    }

    /** A single UPDATE owns each selected row before returning its fresh fencing token. */
    public Optional<Job> claim(Duration lease) {
        long millis = positiveMillis(lease);
        return jdbc.sql("""
            WITH exhausted_candidates AS (
                SELECT generation_key FROM route_display_generation_job
                WHERE attempts >= attempt_limit
                  AND (status='PENDING' OR (status='RUNNING' AND lease_until <= clock_timestamp()))
                ORDER BY updated_at,generation_key FOR UPDATE SKIP LOCKED LIMIT 100
            ), exhausted AS (
                UPDATE route_display_generation_job job SET status='FAILED',lease_token=NULL,lease_until=NULL,
                    failed_at=clock_timestamp(),updated_at=clock_timestamp(),
                    last_failure_code=CASE WHEN job.status='RUNNING' THEN 'LEASE_EXPIRED'
                        ELSE coalesce(job.last_failure_code,'ATTEMPTS_EXHAUSTED') END,
                    last_failure_at=CASE WHEN job.status='RUNNING' THEN clock_timestamp()
                        ELSE coalesce(job.last_failure_at,clock_timestamp()) END
                FROM exhausted_candidates old WHERE job.generation_key=old.generation_key
                RETURNING job.generation_key
            ), candidate AS (
                SELECT generation_key FROM route_display_generation_job
                WHERE attempts < attempt_limit
                  AND ((status='PENDING' AND next_attempt_at <= clock_timestamp())
                    OR (status='RUNNING' AND lease_until <= clock_timestamp()))
                ORDER BY CASE WHEN algorithm_revision LIKE '%/source-only' THEN 0
                    WHEN algorithm_revision='native-reference-shadow-v1' THEN 2 ELSE 1 END,
                    next_attempt_at, created_at, generation_key
                FOR UPDATE SKIP LOCKED LIMIT 1
            )
            UPDATE route_display_generation_job job
            SET status='RUNNING', lease_token=?,
                lease_until=clock_timestamp() + (? * interval '1 millisecond'),
                attempts=attempts+1, updated_at=clock_timestamp()
            FROM candidate WHERE job.generation_key=candidate.generation_key
            RETURNING job.generation_key,job.route_id,job.input_digest,job.evidence_revision,
                job.algorithm_revision,job.input_json::text,job.lease_token,job.attempts,
                greatest(0,extract(epoch FROM (clock_timestamp()-job.created_at))*1000)::bigint,job.source_manifest_id,job.created_at
            """).params(UUID.randomUUID(), millis).query((row, index) -> new Job(
                row.getString(1), row.getString(2), row.getString(3), row.getString(4),
                row.getString(5), row.getString(6), row.getObject(7, UUID.class), row.getInt(8), row.getLong(9),row.getString(10),row.getTimestamp(11).toInstant()))
            .optional();
    }

    /** Caller must hold the current route's lock and recheck source identity in this transaction. */
    @Transactional
    public boolean complete(Job job, String document, String sha256) {
        if (!sha256(document).equals(sha256)) {
            throw new IllegalArgumentException("Artifact digest does not match serialized document");
        }
        // Lock before inserting; an expired/reclaimed worker must never publish an artifact.
        var owned = jdbc.sql("""
            SELECT generation_key FROM route_display_generation_job
            WHERE generation_key=? AND status='RUNNING' AND lease_token=?
                AND lease_until > clock_timestamp() FOR UPDATE
            """).params(job.generationKey(), job.token()).query(String.class).optional();
        if (owned.isEmpty()) return false;
        jdbc.sql("""
            INSERT INTO route_display_automatic_artifact
                (generation_key,route_id,input_digest,evidence_revision,algorithm_revision,document,document_sha256,source_manifest_id)
            SELECT generation_key,route_id,input_digest,evidence_revision,algorithm_revision,?,?,source_manifest_id
            FROM route_display_generation_job WHERE generation_key=?
            """).params(document, sha256, job.generationKey()).update();
        jdbc.sql("""
            UPDATE route_display_generation_job SET status='COMPLETED',lease_token=NULL,
                lease_until=NULL,failed_at=NULL,updated_at=clock_timestamp() WHERE generation_key=?
            """).param(job.generationKey()).update();
        return true;
    }

    public boolean fail(Job job, Duration retryDelay) {
        return failWithOutcome(job, retryDelay, "EXECUTION_ERROR") != FailureOutcome.OWNERSHIP_LOST;
    }

    public enum FailureOutcome { RETRY_SCHEDULED, FAILED, OWNERSHIP_LOST }

    public FailureOutcome failWithOutcome(Job job, Duration retryDelay, String failureCode) {
        long millis = positiveMillis(retryDelay);
        requireFailureCode(failureCode);
        var status = jdbc.sql("""
            UPDATE route_display_generation_job
            SET status=CASE WHEN attempts >= attempt_limit THEN 'FAILED' ELSE 'PENDING' END,
                lease_token=NULL,lease_until=NULL,
                next_attempt_at=clock_timestamp() + (? * interval '1 millisecond'),updated_at=clock_timestamp(),
                last_failure_code=?,last_failure_at=clock_timestamp(),
                failed_at=CASE WHEN attempts >= attempt_limit THEN clock_timestamp() ELSE NULL END
            WHERE generation_key=? AND status='RUNNING' AND lease_token=? AND lease_until > clock_timestamp()
            RETURNING status
            """).params(millis, failureCode, job.generationKey(), job.token()).query(String.class).optional();
        return status.map(value -> value.equals("FAILED") ? FailureOutcome.FAILED : FailureOutcome.RETRY_SCHEDULED)
            .orElse(FailureOutcome.OWNERSHIP_LOST);
    }

    /** Invalid immutable snapshots cannot be repaired by repeating the same job. */
    public boolean reject(Job job, String failureCode) {
        requireFailureCode(failureCode);
        return jdbc.sql("""
            UPDATE route_display_generation_job SET status='FAILED',lease_token=NULL,lease_until=NULL,
                last_failure_code=?,last_failure_at=clock_timestamp(),failed_at=clock_timestamp(),updated_at=clock_timestamp()
            WHERE generation_key=? AND status='RUNNING' AND lease_token=? AND lease_until > clock_timestamp()
            """).params(failureCode, job.generationKey(), job.token()).update() == 1;
    }

    public boolean supersede(Job job) {
        return jdbc.sql("""
            UPDATE route_display_generation_job SET status='SUPERSEDED',lease_token=NULL,lease_until=NULL,
                updated_at=clock_timestamp()
            WHERE generation_key=? AND status='RUNNING' AND lease_token=? AND lease_until > clock_timestamp()
            """).params(job.generationKey(), job.token()).update() == 1;
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static long positiveMillis(Duration value) {
        long millis = value.toMillis();
        if (millis <= 0) throw new IllegalArgumentException("Duration must be at least one millisecond");
        return millis;
    }

    private static void requireFailureCode(String value) {
        if (value == null || !value.matches("[A-Z0-9_]{1,64}"))
            throw new IllegalArgumentException("Invalid generation failure code");
    }
}
