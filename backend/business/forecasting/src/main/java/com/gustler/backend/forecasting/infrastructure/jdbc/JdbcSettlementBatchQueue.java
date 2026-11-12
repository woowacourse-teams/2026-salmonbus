package com.gustler.backend.forecasting.infrastructure.jdbc;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

final class JdbcSettlementBatchQueue {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String READY = """
        q.retry_at<=statement_timestamp() AND (q.lease_until IS NULL OR q.lease_until<=statement_timestamp())
        AND (q.enqueue_generation>q.attempted_generation OR q.evidence='[]'::jsonb
            OR q.source_at+interval '2 hours'<:now
            OR EXISTS (
                SELECT 1 FROM jsonb_to_recordset(q.evidence) AS e(id bigint,facts bigint)
                LEFT JOIN alighting_settlement_projection p ON p.id=e.id
                LEFT JOIN alighting_settlement_generation g ON g.route_id=p.route_id AND g.source_date=p.source_date
                LEFT JOIN route r ON r.id=p.route_id
                WHERE p.id IS NULL OR NOT p.active OR p.dirty OR p.facts_version<>e.facts
                  OR p.input_generation<>g.generation OR p.data_revision<>r.data_revision
                  OR p.next_input_at<=least(CAST(:now AS timestamptz),q.source_at+interval '2 hours')
            ))
        """;
    record Batch(long id, String label, long route, long generation, UUID lease, long attempted, Instant sourceAt, String evidence) { }
    private record Key(long observation, int stop, String target, String label, String due) { }
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    JdbcSettlementBatchQueue(JdbcClient jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        tx = new TransactionTemplate(transactions);
        tx.setTimeout(15);
    }

    boolean backfill() {
        return Boolean.TRUE.equals(tx.execute(status -> {
            Key cursor = jdbc.sql("""
                SELECT observation_id,stop_order,target_kind,label_version FROM alighting_settlement_queue_scan
                WHERE NOT complete FOR UPDATE SKIP LOCKED
                """).query((rs, n) -> new Key(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getString(4), null))
                .optional().orElse(null);
            if (cursor == null) {
                return false;
            }
            List<Key> page = jdbc.sql("""
                SELECT vehicle_observation_id,target_stop_order,target_kind,label_version,next_attempt_at
                FROM alighting_forecast_evaluation_work
                WHERE (vehicle_observation_id,target_stop_order,target_kind,label_version)>(:id,:stop,:target,:label)
                ORDER BY vehicle_observation_id,target_stop_order,target_kind,label_version LIMIT 1000
                """).param("id", cursor.observation()).param("stop", cursor.stop()).param("target", cursor.target())
                .param("label", cursor.label()).query((rs, n) -> new Key(rs.getLong(1), rs.getInt(2), rs.getString(3),
                    rs.getString(4), rs.getString(5))).list();
            if (!page.isEmpty()) {
                jdbc.sql("""
                    SELECT enqueue_alighting_settlement_batch(min(x.observation),x.label,min(x.due))
                    FROM jsonb_to_recordset(CAST(:rows AS jsonb)) AS x(observation bigint,label varchar,due timestamptz)
                    JOIN vehicle_observation o ON o.id=x.observation
                    WHERE x.label IN ('alighting-confirmed-capacity-snapshot-v3','les-confirmed-capacity-snapshot-v3')
                    GROUP BY o.observation_batch_id,x.label
                    """).param("rows", JSON.writeValueAsString(page)).query((rs, n) -> 1).list();
            }
            Key last = page.isEmpty() ? cursor : page.getLast();
            jdbc.sql("""
                UPDATE alighting_settlement_queue_scan SET observation_id=:id,stop_order=:stop,target_kind=:target,
                    label_version=:label,complete=:complete
                """).param("id", last.observation()).param("stop", last.stop()).param("target", last.target())
                .param("label", last.label()).param("complete", page.size() < 1000).update();
            return !page.isEmpty();
        }));
    }

    List<Long> routes(Instant now) {
        return jdbc.sql("""
            SELECT v.id FROM route_version v JOIN LATERAL (
                SELECT 1 FROM alighting_settlement_batch_work q WHERE q.route_version_id=v.id AND %s
                LIMIT 1
            ) ready ON true
            """.formatted(READY)).param("now", offset(now)).query(Long.class).list();
    }

    Batch claim(long route, Instant now, java.util.Set<Long> blocked) {
        return tx.execute(status -> jdbc.sql("""
            UPDATE alighting_settlement_batch_work q SET lease_token=:lease,lease_until=clock_timestamp()+interval '2 minutes'
            FROM (SELECT batch_id,label_version FROM alighting_settlement_batch_work q
                  WHERE q.route_version_id=:route AND q.batch_id NOT IN (:blocked) AND %s ORDER BY q.source_at,q.batch_id,q.label_version
                  LIMIT 1 FOR UPDATE SKIP LOCKED) picked
            WHERE q.batch_id=picked.batch_id AND q.label_version=picked.label_version
            RETURNING q.batch_id,q.label_version,q.route_version_id,q.enqueue_generation,q.lease_token,q.attempted_generation,q.source_at,q.evidence::text
            """.formatted(READY)).param("route", route).param("now", offset(now)).param("lease", UUID.randomUUID()).param("blocked", blocked.isEmpty() ? List.of(0L) : blocked)
            .query((rs, n) -> new Batch(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getObject(5, UUID.class), rs.getLong(6), rs.getObject(7, OffsetDateTime.class).toInstant(), rs.getString(8)))
            .optional().orElse(null));
    }

    boolean unchanged(Batch batch, List<JdbcSettlementProjection.Ref> refs, Instant now) {
        if (batch.generation() != batch.attempted() || batch.sourceAt().plusSeconds(7200).isBefore(now)) {
            return false;
        }
        var prior = JSON.readTree(batch.evidence());
        return prior.size() == refs.size() && refs.stream().allMatch(ref -> {
            for (var item : prior) {
                if (item.path("id").asLong() == ref.id() && item.path("facts").asLong() == ref.facts()) {
                    return true;
                }
            }
            return false;
        });
    }

    void release(Batch batch, List<JdbcSettlementProjection.Ref> refs, boolean attempted, Instant dueThrough) {
        tx.executeWithoutResult(status -> {
            jdbc.sql("""
                UPDATE alighting_settlement_batch_work q SET lease_token=NULL,lease_until=NULL,
                    attempted_generation=CASE WHEN :attempted THEN :generation ELSE attempted_generation END,
                    evidence=CASE WHEN :attempted THEN CAST(:refs AS jsonb) ELSE '[]'::jsonb END,
                    retry_at=coalesce((
                        SELECT min(w.next_attempt_at) FROM vehicle_observation o
                        JOIN alighting_forecast_evaluation_work w ON w.vehicle_observation_id=o.id
                        WHERE o.observation_batch_id=q.batch_id AND w.label_version=q.label_version
                    ),:now)
                WHERE q.batch_id=:batch AND q.label_version=:label AND q.lease_token=:lease
                """).param("attempted", attempted).param("generation", batch.generation())
                .param("refs", JSON.writeValueAsString(refs)).param("now", offset(dueThrough))
                .param("batch", batch.id()).param("label", batch.label()).param("lease", batch.lease()).update();
            jdbc.sql("""
                DELETE FROM alighting_settlement_batch_work q WHERE batch_id=:batch AND label_version=:label
                AND NOT EXISTS(SELECT 1 FROM vehicle_observation o JOIN alighting_forecast_evaluation_work w
                    ON w.vehicle_observation_id=o.id WHERE o.observation_batch_id=q.batch_id AND w.label_version=q.label_version)
                """).param("batch", batch.id()).param("label", batch.label()).update();
        });
    }

    private static OffsetDateTime offset(Instant at) {
        return at.atOffset(ZoneOffset.UTC);
    }
}
