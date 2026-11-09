package com.gustler.backend.archive;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class ArchiveRepository {
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public ArchiveRepository(JdbcClient jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        transaction = new TransactionTemplate(manager);
        transaction.setTimeout(15);
    }

    public record Job(UUID id, long version, LocalDate date, Instant cutoff, UUID token, String state) {
        Instant from() {
            return ArchiveRetention.start(date);
        }

        Instant until() {
            return ArchiveRetention.start(date.plusDays(1));
        }
    }

    public record File(int number, String table, String key, String hash, int rows, int bytes, boolean purged) {
    }

    public record Page(List<String> rows, String cursor) {
        public Page {
            rows = List.copyOf(rows);
        }
    }

    private OffsetDateTime time(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private void limits() {
        jdbc.sql("SET LOCAL statement_timeout='10s'").update();
        jdbc.sql("SET LOCAL lock_timeout='200ms'").update();
        jdbc.sql("SET LOCAL max_parallel_workers_per_gather=0").update();
        jdbc.sql("SET LOCAL TIME ZONE 'UTC'").update();
    }

    /** 후보 날짜는 원본 batch 인덱스로 찾고, 실패 노선은 retry_at 동안 건너뛴다. */
    public Optional<Job> claim(Instant cutoff) {
        return transaction.execute(status -> {
            limits();
            jdbc.sql(
                """
                    INSERT INTO operational_archive_job(id,route_version_id,source_date,cutoff,state,lease_token,lease_until)
                    SELECT gen_random_uuid(),v.id,c.source_date,:cutoff,'EXPORTING',gen_random_uuid(),'-infinity'
                    FROM route_version v CROSS JOIN LATERAL (
                        SELECT (COALESCE(b.response_received_at,b.scheduled_at) AT TIME ZONE 'Asia/Seoul')::date AS source_date
                        FROM observation_batch b WHERE b.route_version_id=v.id AND COALESCE(b.response_received_at,b.scheduled_at)<:cutoff
                        ORDER BY COALESCE(b.response_received_at,b.scheduled_at),b.id LIMIT 1
                    ) c ON CONFLICT(route_version_id,source_date) DO NOTHING
                    """)
                .param("cutoff", time(cutoff)).update();
            return jdbc.sql("""
                UPDATE operational_archive_job SET lease_token=gen_random_uuid(),
                    lease_until=clock_timestamp()+interval '5 minutes',last_attempt_at=clock_timestamp(),
                        cutoff=CASE WHEN state='EXPORTING' THEN GREATEST(cutoff,:cutoff) ELSE cutoff END
                WHERE id=(SELECT id FROM operational_archive_job
                    WHERE state<>'DONE' AND retry_at<=clock_timestamp() AND lease_until<=clock_timestamp()
                    ORDER BY last_attempt_at,id FOR UPDATE SKIP LOCKED LIMIT 1)
                RETURNING id,route_version_id,source_date,cutoff,lease_token,state
                """).param("cutoff", time(cutoff))
                .query((rs, n) -> new Job(rs.getObject(1, UUID.class), rs.getLong(2), rs.getObject(3, LocalDate.class),
                    rs.getObject(4, OffsetDateTime.class).toInstant(), rs.getObject(5, UUID.class), rs.getString(6)))
                .optional();
        });
    }

    private void owned(Job job) {
        final int changed = jdbc.sql("""
            UPDATE operational_archive_job SET lease_until=clock_timestamp()+interval '5 minutes'
            WHERE id=? AND lease_token=? AND lease_until>clock_timestamp() AND state<>'DONE'
            """).params(job.id(), job.token()).update();
        if (changed != 1) {
            throw new IllegalStateException("ARCHIVE_LEASE_LOST");
        }
    }

    private void routeLock(Job job) {
        jdbc.sql("SELECT r.id FROM route r JOIN route_version v ON v.route_id=r.id WHERE" + " v.id=? FOR UPDATE OF r")
            .param(job.version()).query(Long.class).single();
    }

    public String readiness(Job job) {
        return transaction.execute(status -> {
            limits();
            owned(job);
            routeLock(job);
            return ready(job);
        });
    }

    private String ready(Job job) {
        final boolean collecting = jdbc.sql("""
            SELECT EXISTS(SELECT 1 FROM observation_batch
                WHERE route_version_id=:v AND scheduled_at<:until AND outcome IN('RESERVED','DISPATCHING'))
            """).param("v", job.version()).param("until", time(job.until())).query(Boolean.class).single();
        if (collecting) {
            return "COLLECTION_PENDING";
        }
        // work/sample를 묶음으로 조회한다. 개별 관측 참조 SELECT는 없다.
        final boolean pending = jdbc.sql("""
            SELECT EXISTS(SELECT 1 FROM forecast_evaluation_work w
                JOIN vehicle_observation o ON o.id=w.vehicle_observation_id
                JOIN observation_batch b ON b.id=o.observation_batch_id
                WHERE w.route_version_id=:v AND COALESCE(b.response_received_at,b.scheduled_at)<:until)
            """).param("v", job.version()).param("until", time(job.until())).query(Boolean.class).single();
        if (pending) {
            return "SETTLEMENT_PENDING";
        }
        final boolean samples = jdbc.sql("""
            SELECT EXISTS(SELECT 1 FROM stop_demand_pending_sample s
                JOIN vehicle_observation o ON o.id=s.prediction_observation_id
                JOIN observation_batch b ON b.id=o.observation_batch_id
                WHERE s.route_version_id=:v AND COALESCE(b.response_received_at,b.scheduled_at)<:until)
            """).param("v", job.version()).param("until", time(job.until())).query(Boolean.class).single();
        if (samples) {
            return "STATISTICS_PENDING";
        }
        final boolean completed = jdbc.sql("""
            SELECT EXISTS(SELECT 1 FROM stop_demand_baseline base
                JOIN stop_demand_run run USING(route_version_id)
                JOIN route_version v ON v.id=base.route_version_id JOIN route r ON r.id=v.route_id
                WHERE base.route_version_id=:v AND base.initialized AND run.phase='DONE'
                  AND run.quality_revision=r.data_revision)
            """).param("v", job.version()).query(Boolean.class).single();
        if (!completed) {
            return "STATISTICS_NOT_COMPLETED";
        }
        final boolean beyond = jdbc
            .sql(
                """
                    SELECT EXISTS(SELECT 1 FROM forecast_evaluation_result e
                        JOIN vehicle_observation o ON o.id=e.vehicle_observation_id
                        JOIN observation_batch b ON b.id=o.observation_batch_id
                        JOIN stop_demand_run run ON run.route_version_id=e.route_version_id
                        WHERE e.route_version_id=:v AND COALESCE(b.response_received_at,b.scheduled_at)>=:from AND COALESCE(b.response_received_at,b.scheduled_at)<:until
                          AND e.scored_at>run.data_until)
                    """)
            .param("v", job.version()).param("from", time(job.from())).param("until", time(job.until()))
            .query(Boolean.class).single();
        if (beyond) {
            return "LATE_SETTLEMENT_NOT_PUBLISHED";
        }
        final boolean recent = jdbc
            .sql(
                """
                    SELECT EXISTS(SELECT 1 FROM seat_forecast f JOIN vehicle_observation o ON o.id=f.vehicle_observation_id
                        JOIN observation_batch b ON b.id=o.observation_batch_id
                        LEFT JOIN forecast_evaluation_result e USING(vehicle_observation_id,target_stop_order)
                        LEFT JOIN forecast_evaluation_compact c USING(vehicle_observation_id,target_stop_order)
                        WHERE f.route_version_id=:v AND COALESCE(b.response_received_at,b.scheduled_at)>=:from AND COALESCE(b.response_received_at,b.scheduled_at)<:until
                          AND (f.generated_at>=:cutoff OR e.scored_at>=:cutoff OR e.arrived_at>=:cutoff
                            OR c.scored_at>=:cutoff OR c.arrived_at>=:cutoff))
                    """)
            .param("v", job.version()).param("from", time(job.from())).param("until", time(job.until()))
            .param("cutoff", time(job.cutoff())).query(Boolean.class).single();
        if (recent) {
            return "RECENT_CHILD_RECORD";
        }
        return "READY";
    }

    public void closeRange(Job job) {
        transaction.executeWithoutResult(status -> {
            limits();
            owned(job);
            routeLock(job);
            String reason = ready(job);
            if (!reason.equals("READY")) {
                throw new IllegalStateException(reason);
            }
            jdbc.sql("""
                INSERT INTO operational_archive_boundary VALUES(?,?)
                ON CONFLICT(route_version_id) DO UPDATE SET closed_before=GREATEST(
                    operational_archive_boundary.closed_before,EXCLUDED.closed_before)
                """).params(job.version(), time(job.until())).update();
        });
    }

    public Page page(
        Job job,
        ArchiveTable table,
        String cursor,
        final int limit
    ) {
        return transaction.execute(status -> {
            limits();
            owned(job);
            String sql = "SELECT to_jsonb(t)::text AS original," + table.key() + "::text AS cursor FROM " + table.table
                + " t " + table.selection() + " WHERE " + table.batch() + ".route_version_id=:v AND " + "COALESCE("
                + table.batch() + ".response_received_at," + table.batch() + ".scheduled_at)>=:from AND " + "COALESCE("
                + table.batch() + ".response_received_at," + table.batch() + ".scheduled_at)<:until AND "
                + table.afterCursor() + " ORDER BY " + table.keyOrder() + " LIMIT :limit";
            List<String[]> rows = jdbc.sql(sql).param("v", job.version()).param("from", time(job.from()))
                .param("until", time(job.until())).param("cursor", cursor).param("limit", limit)
                .query((rs, n) -> new String[] { rs.getString(1), rs.getString(2) }).list();
            return new Page(rows.stream().map(row -> row[0]).toList(), nextCursor(rows, cursor));
        });
    }

    public Page evidence(Job job, final long cursor, final int limit) {
        return transaction.execute(status -> {
            limits();
            owned(job);
            List<String[]> rows = jdbc
                .sql(
                    """
                            WITH evidence_ids AS MATERIALIZED (
                            SELECT e.arrival_observation_id AS id
                            FROM observation_batch b JOIN vehicle_observation src ON src.observation_batch_id=b.id
                            JOIN forecast_evaluation_result e ON e.vehicle_observation_id=src.id
                            WHERE b.route_version_id=:v AND COALESCE(b.response_received_at,b.scheduled_at)>=:from
                              AND COALESCE(b.response_received_at,b.scheduled_at)<:until AND e.arrival_observation_id IS NOT NULL
                            UNION
                            SELECT c.arrival_observation_id
                            FROM observation_batch b JOIN vehicle_observation src ON src.observation_batch_id=b.id
                            JOIN forecast_evaluation_compact c ON c.vehicle_observation_id=src.id
                            WHERE b.route_version_id=:v AND COALESCE(b.response_received_at,b.scheduled_at)>=:from
                              AND COALESCE(b.response_received_at,b.scheduled_at)<:until
                        )
                        SELECT o.id,jsonb_build_object('observation',to_jsonb(o),'batch',to_jsonb(ab))::text AS original
                        FROM evidence_ids refs JOIN vehicle_observation o ON o.id=refs.id
                        JOIN observation_batch ab ON ab.id=o.observation_batch_id
                        WHERE o.id>:cursor ORDER BY o.id LIMIT :limit
                        """)
                .param("v", job.version()).param("from", time(job.from())).param("until", time(job.until()))
                .param("cursor", cursor).param("limit", limit)
                .query((rs, n) -> new String[] { rs.getString(1), rs.getString(2) }).list();
            return new Page(rows.stream().map(row -> row[1]).toList(), nextEvidenceCursor(rows, cursor));
        });
    }

    private String nextCursor(List<String[]> rows, String previous) {
        if (rows.isEmpty()) {
            return previous;
        }
        return rows.getLast()[1];
    }

    private String nextEvidenceCursor(List<String[]> rows, final long previous) {
        if (rows.isEmpty()) {
            return Long.toString(previous);
        }
        return rows.getLast()[0];
    }

    public List<String> dimensions(Job job) {
        return jdbc
            .sql(
                """
                    SELECT jsonb_build_object('table','route_version','data',to_jsonb(v))::text FROM route_version v WHERE id=:v
                    UNION ALL SELECT jsonb_build_object('table','route','data',to_jsonb(r))::text
                        FROM route r JOIN route_version v ON v.route_id=r.id WHERE v.id=:v
                    UNION ALL SELECT jsonb_build_object('table','route_stop','data',to_jsonb(s))::text FROM route_stop s WHERE route_version_id=:v
                    UNION ALL SELECT jsonb_build_object('table','model_deployment','data',to_jsonb(m))::text FROM model_deployment m
                        WHERE id IN(SELECT DISTINCT f.model_deployment_id FROM seat_forecast f
                            JOIN vehicle_observation o ON o.id=f.vehicle_observation_id
                            JOIN observation_batch b ON b.id=o.observation_batch_id
                            WHERE f.route_version_id=:v AND COALESCE(b.response_received_at,b.scheduled_at)>=:from AND COALESCE(b.response_received_at,b.scheduled_at)<:until)
                    UNION ALL SELECT jsonb_build_object('table','vehicle_capacity','data',to_jsonb(c))::text FROM vehicle_capacity c
                        WHERE vehicle_id IN(SELECT DISTINCT o.vehicle_id FROM vehicle_observation o
                            JOIN observation_batch b ON b.id=o.observation_batch_id
                            WHERE o.route_version_id=:v AND COALESCE(b.response_received_at,b.scheduled_at)>=:from AND COALESCE(b.response_received_at,b.scheduled_at)<:until)
                    UNION ALL SELECT jsonb_build_object('table','demand_statistics_version','data',to_jsonb(d))::text
                        FROM demand_statistics_version d WHERE d.route_version_id=:v
                    UNION ALL SELECT jsonb_build_object('table','vehicle_capacity_history','data',to_jsonb(h))::text
                        FROM vehicle_capacity_history h WHERE h.vehicle_id IN(SELECT DISTINCT o.vehicle_id
                            FROM vehicle_observation o JOIN observation_batch b ON b.id=o.observation_batch_id
                            WHERE o.route_version_id=:v AND COALESCE(b.response_received_at,b.scheduled_at)>=:from AND COALESCE(b.response_received_at,b.scheduled_at)<:until)
                    """)
            .param("v", job.version()).param("from", time(job.from())).param("until", time(job.until()))
            .query(String.class).list();
    }

    public List<File> files(Job job) {
        return jdbc
            .sql("SELECT" + " file_number,table_name,object_key,sha256,row_count,byte_count,purged"
                + " FROM operational_archive_file WHERE job_id=? ORDER BY file_number")
            .param(job.id()).query((rs, n) -> new File(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getInt(5), rs.getInt(6), rs.getBoolean(7)))
            .list();
    }

    public void resetExport(Job job) {
        transaction.executeWithoutResult(status -> {
            limits();
            owned(job);
            jdbc.sql("DELETE FROM operational_archive_file WHERE job_id=?").param(job.id()).update();
        });
    }

    public void recordFile(Job job, File file) {
        transaction.executeWithoutResult(status -> {
            limits();
            owned(job);
            jdbc.sql("INSERT INTO"
                + " operational_archive_file(job_id,file_number,table_name,object_key,sha256,row_count,byte_count)"
                + " VALUES(?,?,?,?,?,?,?)")
                .params(job.id(), file.number(), file.table(), file.key(), file.hash(), file.rows(), file.bytes())
                .update();
        });
    }

    public String manifestHash(Job job) {
        return jdbc.sql("SELECT manifest_sha256 FROM operational_archive_job WHERE id=?").param(job.id())
            .query(String.class).single();
    }

    public void verified(Job job, String hash) {
        transaction.executeWithoutResult(status -> {
            limits();
            owned(job);
            jdbc.sql("UPDATE operational_archive_job SET" + " state='VERIFIED',manifest_sha256=? WHERE id=?")
                .params(hash, job.id()).update();
        });
    }

    /** FK 검사의 전체 원본 반복 스캔을 피한다. 운영 인덱스 생성은 이 앱에서 하지 않는다. */
    public void requireDeletionIndexes() {
        for (String[] required : List.of(new String[] { "forecast_evaluation_result", "arrival_observation_id" },
            new String[] { "forecast_evaluation_compact", "arrival_observation_id" },
            new String[] { "stop_demand_pending_sample", "arrival_observation_id" },
            new String[] { "seat_forecast", "publication_id" })) {
            final boolean indexed = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM pg_index i JOIN pg_attribute a
                    ON a.attrelid=i.indrelid AND a.attnum=i.indkey[0]
                    WHERE i.indrelid=to_regclass(:table) AND a.attname=:column
                      AND i.indisvalid AND i.indpred IS NULL)
                """).param("table", required[0]).param("column", required[1]).query(Boolean.class).single();
            if (!indexed) {
                throw new IllegalStateException("DELETE_INDEX_REQUIRED_" + required[0]);
            }
        }
    }

    public int purge(Job job, File file, List<String> originals) {
        return transaction.execute(status -> {
            limits();
            owned(job);
            routeLock(job);
            if (!ready(job).equals("READY")) {
                throw new IllegalStateException("WORK_NOT_COMPLETED");
            }
            if (jdbc.sql("SELECT purged FROM operational_archive_file WHERE job_id=? AND" + " file_number=? FOR UPDATE")
                .params(job.id(), file.number()).query(Boolean.class).single()) {
                return 0;
            }
            Optional<ArchiveTable> table = Arrays.stream(ArchiveTable.values())
                .filter(t -> t.table.equals(file.table())).findFirst();
            int removed = 0;
            if (table.isPresent()) {
                ArchiveTable t = table.get();
                String json = "[" + String.join(",", originals.stream().map(s -> "{\"original\":" + s + "}").toList())
                    + "]";
                removed = jdbc
                    .sql("DELETE FROM " + t.table + " t USING jsonb_to_recordset(CAST(:rows"
                        + " AS jsonb)) AS r(original jsonb) WHERE " + t.keyMatch() + " AND to_jsonb(t)=r.original")
                    .param("rows", json).update();
                if (removed != file.rows()) {
                    throw new IllegalStateException("SOURCE_CHANGED_OR_MISSING");
                }
            }
            jdbc.sql("UPDATE operational_archive_file SET purged=true WHERE job_id=?" + " AND file_number=?")
                .params(job.id(), file.number()).update();
            jdbc.sql("UPDATE operational_archive_job SET state='PURGING' WHERE id=?").param(job.id()).update();
            return removed;
        });
    }

    public void finish(Job job) {
        transaction.executeWithoutResult(status -> {
            limits();
            owned(job);
            if (jdbc.sql("SELECT EXISTS(SELECT 1 FROM operational_archive_file WHERE" + " job_id=? AND NOT purged)")
                .param(job.id()).query(Boolean.class).single()) {
                throw new IllegalStateException("FILES_NOT_PURGED");
            }
            jdbc.sql("UPDATE operational_archive_job SET"
                + " state='DONE',completed_at=clock_timestamp(),failure_code=NULL,lease_until='-infinity'"
                + " WHERE id=?").param(job.id()).update();
        });
    }

    public void defer(Job job, String code) {
        jdbc.sql("""
            UPDATE operational_archive_job SET failure_code=?,retry_at=clock_timestamp()+interval '60 seconds',
                lease_until=clock_timestamp() WHERE id=? AND lease_token=?
            """).params(code.substring(0, Math.min(80, code.length())), job.id(), job.token()).update();
    }
}
