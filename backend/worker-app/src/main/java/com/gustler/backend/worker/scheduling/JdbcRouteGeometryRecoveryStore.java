package com.gustler.backend.worker.scheduling;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRouteGeometryRecoveryStore {
    private final JdbcClient jdbc;

    public JdbcRouteGeometryRecoveryStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    public record Job(String routeId, long versionId, UUID token, int attempts) {}

    public Optional<Long> request(String routeId, boolean scheduled) {
        String reset = ":scheduled AND route_geometry_recovery.status IN ('SUCCEEDED','EXHAUSTED','SUPERSEDED')";
        return jdbc.sql("""
            INSERT INTO route_geometry_recovery(route_version_id,route_id,status)
            SELECT v.id,r.id,'PENDING' FROM route r
            JOIN route_version v ON v.route_id=r.id AND v.valid_to IS NULL
            WHERE r.public_route_id=:routeId AND r.source_id='GBIS'
            ON CONFLICT(route_version_id) DO UPDATE SET
                status=CASE WHEN %1$s THEN 'PENDING' ELSE route_geometry_recovery.status END,
                attempts=CASE WHEN %1$s THEN 0 ELSE route_geometry_recovery.attempts END,
                next_attempt_at=CASE WHEN %1$s THEN clock_timestamp() ELSE route_geometry_recovery.next_attempt_at END,
                cycle_started_at=CASE WHEN %1$s THEN clock_timestamp() ELSE route_geometry_recovery.cycle_started_at END,
                last_outcome=CASE WHEN %1$s THEN NULL ELSE route_geometry_recovery.last_outcome END,
                updated_at=CASE WHEN %1$s THEN clock_timestamp() ELSE route_geometry_recovery.updated_at END
            RETURNING route_version_id
            """.formatted(reset)).param("scheduled",scheduled).param("routeId",routeId).query(Long.class).optional();
    }

    public Optional<Job> claim(Long versionId) {
        jdbc.sql("""
            UPDATE route_geometry_recovery j SET status='SUPERSEDED',lease_token=NULL,lease_until=NULL,
                updated_at=clock_timestamp()
            WHERE status IN ('PENDING','RUNNING') AND NOT EXISTS
                (SELECT 1 FROM route_version v WHERE v.id=j.route_version_id AND v.route_id=j.route_id AND v.valid_to IS NULL)
            """).update();
        jdbc.sql("""
            UPDATE route_geometry_recovery SET status='EXHAUSTED',lease_token=NULL,lease_until=NULL,
                last_outcome='LEASE_EXPIRED',updated_at=clock_timestamp()
            WHERE status='RUNNING' AND attempts>=5 AND lease_until<=clock_timestamp()
            """).update();
        return jdbc.sql("""
            WITH due AS (
                SELECT j.route_version_id FROM route_geometry_recovery j
                JOIN route_version v ON v.id=j.route_version_id AND v.route_id=j.route_id AND v.valid_to IS NULL
                WHERE (CAST(:target AS bigint) IS NULL OR j.route_version_id=:target) AND j.attempts<5
                    AND ((j.status='PENDING' AND j.next_attempt_at<=clock_timestamp())
                        OR (j.status='RUNNING' AND j.lease_until<=clock_timestamp()))
                ORDER BY j.next_attempt_at,j.route_version_id FOR UPDATE OF j SKIP LOCKED LIMIT 1
            )
            UPDATE route_geometry_recovery j SET status='RUNNING',attempts=j.attempts+1,
                lease_token=:token,lease_until=clock_timestamp()+interval '5 minutes',updated_at=clock_timestamp()
            FROM due,route r WHERE j.route_version_id=due.route_version_id AND r.id=j.route_id
            RETURNING r.public_route_id,j.route_version_id,j.lease_token,j.attempts
            """).param("target",versionId).param("token",UUID.randomUUID())
            .query((r,n)->new Job(r.getString(1),r.getLong(2),r.getObject(3,UUID.class),r.getInt(4))).optional();
    }

    public boolean current(Job job) {
        return jdbc.sql("""
            SELECT EXISTS(SELECT 1 FROM route_version v JOIN route r ON r.id=v.route_id
                WHERE v.id=? AND v.valid_to IS NULL AND r.public_route_id=? AND r.source_id='GBIS')
            """).params(job.versionId(),job.routeId()).query(Boolean.class).single();
    }

    public boolean finish(Job job, boolean retry, boolean obsolete, String outcome) {
        if (outcome == null || !outcome.matches("[A-Z_]{1,40}")) throw new IllegalArgumentException("Invalid recovery outcome");
        String status=obsolete?"SUPERSEDED":retry?(job.attempts()>=5?"EXHAUSTED":"PENDING"):"SUCCEEDED";
        long delay=10L << Math.min(job.attempts()-1,3);
        return jdbc.sql("""
            UPDATE route_geometry_recovery SET status=?,next_attempt_at=clock_timestamp()+(? * interval '1 second'),
                lease_token=NULL,lease_until=NULL,last_outcome=?,updated_at=clock_timestamp()
            WHERE route_version_id=? AND status='RUNNING' AND lease_token=? AND lease_until>clock_timestamp()
            """).params(status,delay,outcome,job.versionId(),job.token()).update()==1;
    }

    public Optional<String> status(long versionId) {
        return jdbc.sql("SELECT status FROM route_geometry_recovery WHERE route_version_id=?")
                .param(versionId).query(String.class).optional();
    }
}
