package com.gustler.backend.worker.routegeometry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Durable work ownership. All lease comparisons use the database clock. */
@Repository
public class JdbcRouteDisplayGenerationStore {
    private final JdbcClient jdbc;

    public JdbcRouteDisplayGenerationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
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
                (generation_key,route_id,input_digest,evidence_revision,algorithm_revision,input_json,source_manifest_id)
            VALUES (?,?,?,?,?,CAST(? AS jsonb),?) ON CONFLICT (generation_key) DO UPDATE
            SET status='PENDING',next_attempt_at=clock_timestamp(),updated_at=clock_timestamp()
            WHERE route_display_generation_job.status='SUPERSEDED'
            """).params(key, routeId, inputDigest, evidenceRevision, algorithmRevision, inputJson,sourceManifestId).update();
    }

    /** A single UPDATE owns each selected row before returning its fresh fencing token. */
    public Optional<Job> claim(Duration lease) {
        long millis = positiveMillis(lease);
        return jdbc.sql("""
            WITH candidate AS (
                SELECT generation_key FROM route_display_generation_job
                WHERE (status='PENDING' AND next_attempt_at <= clock_timestamp())
                   OR (status='RUNNING' AND lease_until <= clock_timestamp())
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
                lease_until=NULL,updated_at=clock_timestamp() WHERE generation_key=?
            """).param(job.generationKey()).update();
        return true;
    }

    public boolean fail(Job job, Duration retryDelay) {
        long millis = positiveMillis(retryDelay);
        return jdbc.sql("""
            UPDATE route_display_generation_job SET status='PENDING',lease_token=NULL,lease_until=NULL,
                next_attempt_at=clock_timestamp() + (? * interval '1 millisecond'),updated_at=clock_timestamp()
            WHERE generation_key=? AND status='RUNNING' AND lease_token=? AND lease_until > clock_timestamp()
            """).params(millis, job.generationKey(), job.token()).update() == 1;
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
}
