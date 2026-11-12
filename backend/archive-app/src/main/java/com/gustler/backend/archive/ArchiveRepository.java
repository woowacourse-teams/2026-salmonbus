package com.gustler.backend.archive;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
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

    public record Page(List<String> rows, String cursor, Set<Long> modelDeploymentIds) {
        public Page {
            rows = List.copyOf(rows);
            modelDeploymentIds = Set.copyOf(modelDeploymentIds);
        }
    }

    private record ArchiveRow(String original, String cursor, Long modelDeploymentId) {
    }

    /**
     * 노선 {@code :v} 의 한국 날짜 {@code [:from, :until)} 배치를 고르는 조건이다. 별칭 {@code b} 가 observation_batch 여야 한다.
     *
     * <p>{@code COALESCE(response_received_at, scheduled_at)} 범위와 같은 행을 고른다. 응답 시각이 있으면 응답 시각으로, 없으면 예약
     * 시각으로 날짜를 정한다. 식 그대로 쓰면 ix_batch_recent_history(route_version_id, response_received_at) 의 범위 조건이 되지 못해 노선 전체
     * 배치를 읽으므로, 두 갈래로 나눠 인덱스 범위로 읽게 한다. 응답 없는 갈래는 그 노선의 응답 없는 배치를 기간과 상관없이 인덱스에서 읽는다.
     */
    private static final String DAY_BATCHES = """
        b.route_version_id=:v AND ((b.response_received_at>=:from AND b.response_received_at<:until)
            OR (b.response_received_at IS NULL AND b.scheduled_at>=:from AND b.scheduled_at<:until))""";

    /**
     * 그 노선·날짜의 배치 id 를 먼저 확정한다.
     *
     * <p>EXISTS 나 {@code ORDER BY ... LIMIT} 안에서는 첫 행을 빨리 찾는 계획을 골라 observation_batch 나 원본 표를 기본 키 순서로
     * 끝까지 읽을 수 있어서, {@code MATERIALIZED} 로 배치 범위를 인덱스로 먼저 읽게 고정한다.
     */
    private static final String DAY_BATCHES_CTE =
        "WITH day_batches AS MATERIALIZED (SELECT b.id FROM observation_batch b WHERE " + DAY_BATCHES + ")\n";

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
                    SELECT gen_random_uuid(),v.id,(c.source_at AT TIME ZONE 'Asia/Seoul')::date,
                        :cutoff,'EXPORTING',gen_random_uuid(),'-infinity'
                    FROM route_version v CROSS JOIN LATERAL (
                        SELECT LEAST(
                            (SELECT b.response_received_at FROM observation_batch b
                             WHERE b.route_version_id=v.id AND b.response_received_at<:cutoff
                             ORDER BY b.response_received_at LIMIT 1),
                            (SELECT min(b.scheduled_at) FROM observation_batch b
                             WHERE b.route_version_id=v.id AND b.response_received_at IS NULL AND b.scheduled_at<:cutoff)
                        ) AS source_at
                        OFFSET 0
                    ) c WHERE c.source_at IS NOT NULL
                    ON CONFLICT(route_version_id,source_date) DO NOTHING
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

    /**
     * 이관 대상 노선·날짜를 지금 옮겨도 되는지 판정한다.
     *
     * <p>보관 기준 뒤에 생긴 예보·정산 기록을 찾을 때는 그 노선·날짜의 배치를 {@code MATERIALIZED} CTE 로 먼저 확정하고, 배치마다
     * {@code LIMIT 1} 하위 질의로 관측은 (observation_batch_id, source_row_number) 유일 키로, 예보·정산은 기본 키로 따라간다.
     * seat_forecast 에는 노선 인덱스가 없어 예보 쪽에서 출발하면 표 전체를 읽고, 배치를 먼저 확정하지 않으면 EXISTS 의 첫 행 최적화 때문에
     * observation_batch 전체를 읽을 수 있다. 관측의 (observation_batch_id,
     * route_version_id) FK 와 예보·정산의 (vehicle_observation_id, route_version_id) FK 때문에 배치의 노선 조건은 예보·정산의 노선 조건과
     * 같은 행을 고른다.
     *
     * <p>승하차 정산은 alighting_forecast_evaluation_metric 뷰로 원본과 원본 삭제한 보관을 함께 본다. 뷰의 보관 쪽 갈래에는 보관 상태
     * WHERE 가 있어 UNION ALL 이 펼쳐지지 않으므로, 관측 id 를 조인 조건으로 주면 보관 표 기본 키로 내려가지 못하고 보관 표를 통째로
     * 읽는다. 관측마다 {@code LATERAL} 하위 질의 안에서 관측 id 를 주어 두 갈래 모두 기본 키로 읽게 한다.
     */
    private String ready(Job job) {
        boolean historyRequired = jdbc.sql("""
            SELECT EXISTS (
                SELECT 1
                FROM route_version archived
                JOIN route archived_route ON archived_route.id=archived.route_id
                JOIN route matching ON matching.display_name=archived_route.display_name
                JOIN route_version active ON active.route_id=matching.id AND active.valid_to IS NULL
                LEFT JOIN alighting_history_checkpoint current ON current.route_version_id=active.id
                WHERE archived.id=:version AND (
                    current.retain_from<:until OR (
                        current.route_version_id IS NULL AND EXISTS (
                            SELECT 1 FROM alighting_history_checkpoint previous
                            JOIN route_version v ON v.id=previous.route_version_id
                            WHERE v.route_id=active.route_id
                        )
                    )
                )
            )
            """).param("version", job.version()).param("until", time(job.until())).query(Boolean.class).single();
        if (historyRequired) {
            return "ALIGHTING_HISTORY_REQUIRED";
        }
        final boolean collecting = jdbc.sql("""
            SELECT EXISTS(SELECT 1 FROM observation_batch
                WHERE route_version_id=:v AND scheduled_at<:until AND outcome IN('RESERVED','DISPATCHING'))
            """).param("v", job.version()).param("until", time(job.until())).query(Boolean.class).single();
        if (collecting) {
            return "COLLECTION_PENDING";
        }
        final boolean alightingPending = jdbc.sql("""
            SELECT EXISTS(SELECT 1 FROM alighting_forecast_evaluation_work w
                JOIN vehicle_observation o ON o.id=w.vehicle_observation_id
                JOIN observation_batch b ON b.id=o.observation_batch_id
                WHERE w.route_version_id=:v AND COALESCE(b.response_received_at,b.scheduled_at)<:until)
            """).param("v", job.version()).param("until", time(job.until())).query(Boolean.class).single();
        if (alightingPending) {
            return "ALIGHTING_AWARE_SETTLEMENT_PENDING";
        }
        final boolean recent = jdbc.sql(DAY_BATCHES_CTE + """
            SELECT EXISTS(SELECT 1 FROM day_batches d CROSS JOIN LATERAL (
                SELECT 1 FROM vehicle_observation o JOIN seat_forecast f ON f.vehicle_observation_id=o.id
                LEFT JOIN forecast_evaluation_result e
                    ON e.vehicle_observation_id=f.vehicle_observation_id AND e.target_stop_order=f.target_stop_order
                LEFT JOIN forecast_evaluation_compact c
                    ON c.vehicle_observation_id=f.vehicle_observation_id AND c.target_stop_order=f.target_stop_order
                WHERE o.observation_batch_id=d.id AND f.route_version_id=:v
                  AND (f.generated_at>=:cutoff OR e.scored_at>=:cutoff OR e.arrived_at>=:cutoff
                    OR c.scored_at>=:cutoff OR c.arrived_at>=:cutoff)
                LIMIT 1) changed)
            """)
            .param("v", job.version()).param("from", time(job.from())).param("until", time(job.until()))
            .param("cutoff", time(job.cutoff())).query(Boolean.class).single();
        final boolean recentAlightingAware = jdbc.sql(DAY_BATCHES_CTE + """
            SELECT EXISTS(SELECT 1 FROM day_batches d CROSS JOIN LATERAL (
                SELECT o.id FROM vehicle_observation o WHERE o.observation_batch_id=d.id OFFSET 0) o
            CROSS JOIN LATERAL (
                SELECT 1 FROM alighting_forecast_evaluation_metric e
                WHERE e.vehicle_observation_id=o.id AND e.route_version_id=:v
                  AND (e.scored_at>=:cutoff OR e.event_at>=:cutoff)
                LIMIT 1) changed)
            """)
            .param("v", job.version()).param("from", time(job.from())).param("until", time(job.until()))
            .param("cutoff", time(job.cutoff())).query(Boolean.class).single();
        if (recent || recentAlightingAware) {
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

    /**
     * 이관 대상 노선·날짜에 딸린 {@code table} 행을 키 순서로 커서 뒤에서 {@code limit} 행까지 읽는다.
     *
     * <p>그 노선·날짜의 배치를 {@code MATERIALIZED} CTE 로 먼저 확정하고, {@link ArchiveTable#driver()} 의 배치나 관측에서
     * {@code LATERAL} 로 표의 키를 따라간다. {@code OFFSET 0} 은 하위 질의가 펼쳐지지 않게 해서 이 순서를 고정한다. 표를 기본 키
     * 순서로 걸으며 노선·날짜를 거르는 계획은 {@code LIMIT} 을 못 채우는 마지막 페이지에서 표 끝까지 읽는다.
     */
    public Page page(
        Job job,
        ArchiveTable table,
        String cursor,
        final int limit
    ) {
        return transaction.execute(status -> {
            limits();
            owned(job);
            List<ArchiveRow> rows = jdbc.sql(pageSql(table)).param("v", job.version()).param("from", time(job.from()))
                .param("until", time(job.until())).param("cursor", cursor).param("limit", limit)
                .query((rs, n) -> new ArchiveRow(rs.getString(1), rs.getString(2), rs.getObject(3, Long.class))).list();
            Set<Long> deployments = table == ArchiveTable.FORECAST
                ? rows.stream().map(ArchiveRow::modelDeploymentId).collect(Collectors.toSet()) : Set.of();
            return new Page(rows.stream().map(ArchiveRow::original).toList(),
                rows.isEmpty() ? cursor : rows.getLast().cursor(), deployments);
        });
    }

    private static String pageSql(ArchiveTable table) {
        String deploymentId = table == ArchiveTable.FORECAST ? "t.model_deployment_id" : "NULL::bigint";
        return DAY_BATCHES_CTE + "SELECT r.original,r.cursor,r.model_deployment_id FROM (" + table.driver()
            + ") x CROSS JOIN LATERAL (SELECT to_jsonb(t)::text AS original," + table.key() + "::text AS cursor,"
            + deploymentId + " AS model_deployment_id," + table.keyOrder() + " FROM " + table.table + " t WHERE "
            + table.link() + " AND " + table.afterCursor() + " OFFSET 0) r ORDER BY " + table.pageOrder()
            + " LIMIT :limit";
    }

    /**
     * 이관 대상 노선·날짜 예보의 정산이 가리키는 도착 관측을 관측 id 순서로 커서 뒤에서 {@code limit} 행까지 읽는다.
     *
     * <p>그 노선·날짜의 배치를 {@code MATERIALIZED} CTE 로 먼저 확정하고 관측과 정산을 키로 따라가 도착 관측 id 를 모은다. 모은 id 를
     * 정렬한 뒤 {@code LATERAL} 로 관측과 배치를 기본 키로 읽어 {@code limit} 행을 채우면 멈춘다. 관측 기본 키 순서로 걸으며 모은 id 와
     * 맞추는 계획은 {@code LIMIT} 을 못 채우는 마지막 페이지에서 관측 표 끝까지 읽는다.
     */
    public Page evidence(Job job, final long cursor, final int limit) {
        return transaction.execute(status -> {
            limits();
            owned(job);
            List<String[]> rows = jdbc
                .sql(DAY_BATCHES_CTE + """
                    , evidence_ids AS MATERIALIZED (
                        SELECT e.arrival_observation_id AS id
                        FROM day_batches d CROSS JOIN LATERAL (
                            SELECT src.id FROM vehicle_observation src WHERE src.observation_batch_id=d.id OFFSET 0) src
                        JOIN forecast_evaluation_result e ON e.vehicle_observation_id=src.id
                        WHERE e.arrival_observation_id>:cursor
                        UNION
                        SELECT c.arrival_observation_id
                        FROM day_batches d CROSS JOIN LATERAL (
                            SELECT src.id FROM vehicle_observation src WHERE src.observation_batch_id=d.id OFFSET 0) src
                        JOIN forecast_evaluation_compact c ON c.vehicle_observation_id=src.id
                        WHERE c.arrival_observation_id>:cursor
                    )
                    SELECT r.id,r.original FROM (SELECT id FROM evidence_ids ORDER BY id) refs CROSS JOIN LATERAL (
                        SELECT o.id,jsonb_build_object('observation',to_jsonb(o),'batch',to_jsonb(ab))::text AS original
                        FROM vehicle_observation o JOIN observation_batch ab ON ab.id=o.observation_batch_id
                        WHERE o.id=refs.id OFFSET 0) r
                    ORDER BY refs.id LIMIT :limit
                    """)
                .param("v", job.version()).param("from", time(job.from())).param("until", time(job.until()))
                .param("cursor", cursor).param("limit", limit)
                .query((rs, n) -> new String[] { rs.getString(1), rs.getString(2) }).list();
            return new Page(rows.stream().map(row -> row[1]).toList(), nextEvidenceCursor(rows, cursor), Set.of());
        });
    }

    private String nextEvidenceCursor(List<String[]> rows, final long previous) {
        if (rows.isEmpty()) {
            return Long.toString(previous);
        }
        return rows.getLast()[0];
    }

    public List<String> dimensions(Job job, String deploymentIdsJson) {
        return transaction.execute(status -> {
            limits();
            owned(job);
            return jdbc
            .sql(
                """
                    SELECT jsonb_build_object('table','route_version','data',to_jsonb(v))::text FROM route_version v WHERE id=:v
                    UNION ALL SELECT jsonb_build_object('table','route','data',to_jsonb(r))::text
                        FROM route r JOIN route_version v ON v.route_id=r.id WHERE v.id=:v
                    UNION ALL SELECT jsonb_build_object('table','route_stop','data',to_jsonb(s))::text FROM route_stop s WHERE route_version_id=:v
                    UNION ALL SELECT jsonb_build_object('table','model_deployment','data',to_jsonb(m))::text FROM model_deployment m
                        WHERE m.id IN (SELECT value::bigint FROM jsonb_array_elements_text(CAST(:deployments AS jsonb)))
                    UNION ALL SELECT jsonb_build_object('table','vehicle_capacity','data',to_jsonb(c))::text FROM vehicle_capacity c
                        WHERE vehicle_id IN(SELECT DISTINCT o.vehicle_id FROM vehicle_observation o
                            JOIN observation_batch b ON b.id=o.observation_batch_id
                            WHERE o.route_version_id=:v AND\s""" + DAY_BATCHES + """
                    )
                    UNION ALL SELECT jsonb_build_object('table','demand_statistics_version','data',to_jsonb(d))::text
                        FROM demand_statistics_version d WHERE d.route_version_id=:v
                    UNION ALL SELECT jsonb_build_object('table','vehicle_capacity_history','data',to_jsonb(h))::text
                        FROM vehicle_capacity_history h WHERE h.vehicle_id IN(SELECT DISTINCT o.vehicle_id
                            FROM vehicle_observation o JOIN observation_batch b ON b.id=o.observation_batch_id
                            WHERE o.route_version_id=:v AND\s""" + DAY_BATCHES + ")")
            .param("v", job.version()).param("from", time(job.from())).param("until", time(job.until()))
                .param("deployments", deploymentIdsJson).query(String.class).list();
        });
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
            new String[] { "seat_forecast", "publication_id" },
            new String[] { "alighting_forecast_evaluation_result", "pre_observation_id" },
            new String[] { "alighting_forecast_evaluation_result", "post_observation_id" },
            new String[] { "alighting_forecast_evaluation_result", "closure_observation_id" },
            new String[] { "alighting_forecast_evaluation_result", "previous_post_observation_id" },
            new String[] { "alighting_forecast_evaluation_result", "previous_closure_observation_id" })) {
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
                .filter(t -> t.table.equals(file.table().replaceFirst("^les_", "alighting_"))).findFirst();
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
