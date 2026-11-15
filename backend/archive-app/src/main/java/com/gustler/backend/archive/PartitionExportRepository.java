package com.gustler.backend.archive;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 날짜 파티션 내보내기가 쓰는 카탈로그 조회, 파티션 읽기, 장부({@code daily_export}) 쓰기, 파티션 떼어 지우기를 맡는다.
 * 장부에서는 {@code segment = 0} 줄만 읽고 쓴다. 다른 조각 번호는 옛 테이블 내보내기({@link LegacyExportRepository})의 몫이다.
 *
 * <p>테이블 이름은 {@link #TABLES} 에서만, 파티션 이름은 이름 규칙과 경계를 모두 확인한 카탈로그 행에서만 오므로 SQL 에 그대로 넣는다.
 * 열 이름과 타입 표기도 카탈로그에서 읽고 허용 문자만 받는다. 값은 언제나 bind 한다. {@code route} 행 잠금은 잡지 않는다.
 */
public class PartitionExportRepository {
    /** 날짜 파티션을 내보내는 테이블이다. forecasting 모듈 {@code PartitionMaintenance.TABLES} 와 같은 목록, 같은 순서다. */
    public static final List<String> TABLES =
        List.of("forecast_run", "forecast_record", "stop_pass", "pass_label", "forecast_unresolved");

    static final int ADVISORY_NAMESPACE = 20261115;
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration DROP_STATEMENT_TIMEOUT = Duration.ofSeconds(60);
    private static final DateTimeFormatter SUFFIX = DateTimeFormatter.BASIC_ISO_DATE;
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]{0,62}");
    private static final Pattern TYPE = Pattern.compile("[a-z][a-z0-9 _]*(\\(\\d+(,\\d+)?\\))?(\\[\\])?");

    private static final RowMapper<Ledger> LEDGER = (rs, n) -> new Ledger(rs.getString(1), rs.getLong(2),
        rs.getString(3), instant(rs.getObject(4, OffsetDateTime.class)), instant(rs.getObject(5, OffsetDateTime.class)),
        instant(rs.getObject(6, OffsetDateTime.class)));

    private final JdbcClient jdbc;
    private final TransactionTemplate readOnly;
    private final TransactionTemplate write;
    private final Duration lockTimeout;

    /**
     * @param lockTimeout 장부 잠금과 파티션 떼어 지우기의 잠금 대기 시간이다.
     */
    public PartitionExportRepository(JdbcClient jdbc, PlatformTransactionManager manager, Duration lockTimeout) {
        this.jdbc = jdbc;
        this.lockTimeout = lockTimeout;
        readOnly = new TransactionTemplate(manager);
        readOnly.setReadOnly(true);
        readOnly.setTimeout((int) READ_TIMEOUT.toSeconds() + 15);
        write = new TransactionTemplate(manager);
        write.setTimeout((int) DROP_STATEMENT_TIMEOUT.toSeconds() + 15);
    }

    /** 날짜 파티션 하나. {@code name} 은 {@code <table>_pYYYYMMDD} 다. */
    public record Partition(String table, LocalDate date, String name) {
        static Partition of(String table, LocalDate date) {
            return new Partition(table, date, table + "_p" + date.format(SUFFIX));
        }
    }

    /** 장부 한 줄. {@code key} 와 {@code sha256} 은 manifest 의 키와 해시다. */
    public record Ledger(
        String key,
        long rows,
        String sha256,
        Instant exportedAt,
        Instant verifiedAt,
        Instant droppedAt
    ) {
    }

    /** 키 순서 한 페이지. {@code cursor} 는 마지막 줄의 키를 담은 JSON 배열이다. */
    public record Page(List<String> rows, String cursor) {
        public Page {
            rows = List.copyOf(rows);
        }
    }

    /** 장부에 적은 결과. {@code inserted} 가 거짓이면 다른 실행이 먼저 적은 줄이 {@code ledger} 다. */
    public record LedgerWrite(boolean inserted, Ledger ledger) {
    }

    /** 파티션 떼어 지우기의 결과. */
    public enum DropResult {
        /** 떼어 지우고 장부에 {@code dropped_at} 을 적었다. */
        DROPPED,
        /** 다른 실행이 같은 파티션을 처리하고 있다. */
        BUSY,
        /** 다른 실행이 이미 떼어 지웠다. */
        ALREADY_DROPPED,
        /** 장부가 확인한 값과 달라졌거나 확인 전이다. */
        LEDGER_CHANGED,
        /** 파티션이 이미 없다. */
        GONE,
        /** 지금 파티션의 줄 수가 장부의 줄 수와 다르다. */
        ROW_COUNT_CHANGED
    }

    /**
     * 다섯 테이블의 날짜 파티션 가운데 날짜가 {@code through} 이하인 것을 날짜, 테이블 순서로 낸다.
     *
     * <p>{@code pg_inherits} 에서 부모에 붙은 파티션을 읽고, 이름이 {@code <table>_pYYYYMMDD} 이며 경계가 정확히 그 하루인 것만 고른다.
     * 기본 파티션과 규칙에 맞지 않는 파티션은 내지 않는다. 이름은 규칙에 맞는데 경계가 다른 파티션은 {@code unexpected} 에 담는다.
     */
    public List<Partition> datedPartitions(LocalDate through, List<String> unexpected) {
        List<Partition> found = new ArrayList<>();
        for (String[] row : attachedPartitions()) {
            Matcher name = Pattern.compile(Pattern.quote(row[0]) + "_p(\\d{8})").matcher(row[1]);
            if (!name.matches()) {
                continue;
            }
            LocalDate date;
            try {
                date = LocalDate.parse(name.group(1), SUFFIX);
            } catch (RuntimeException invalid) {
                unexpected.add(row[1]);
                continue;
            }
            if (!expectedBound(date).equals(row[2])) {
                unexpected.add(row[1]);
                continue;
            }
            if (!date.isAfter(through)) {
                found.add(Partition.of(row[0], date));
            }
        }
        found.sort(Comparator.comparing(Partition::date).thenComparing(p -> TABLES.indexOf(p.table())));
        return found;
    }

    /** 다섯 부모 테이블에 붙은 파티션마다 {부모 이름, 파티션 이름, 경계 표기} 를 낸다. */
    private List<String[]> attachedPartitions() {
        String parents = String.join(",", TABLES.stream().map(t -> "to_regclass('" + t + "')").toList());
        return jdbc.sql("""
            SELECT p.relname, c.relname, pg_get_expr(c.relpartbound, c.oid)
            FROM pg_inherits i
            JOIN pg_class c ON c.oid = i.inhrelid
            JOIN pg_class p ON p.oid = i.inhparent
            WHERE i.inhparent IN (""" + parents + ")")
            .query((rs, n) -> new String[] { rs.getString(1), rs.getString(2), rs.getString(3) }).list();
    }

    private static String expectedBound(LocalDate date) {
        return "FOR VALUES FROM ('" + date + "') TO ('" + date.plusDays(1) + "')";
    }

    /** 부모 테이블의 열 이름과 타입을 열 순서대로 낸다. */
    public List<PartitionManifest.Column> columns(String table) {
        requireTable(table);
        List<PartitionManifest.Column> columns = jdbc.sql("""
            SELECT a.attname, format_type(a.atttypid, a.atttypmod)
            FROM pg_attribute a
            WHERE a.attrelid = to_regclass(?) AND a.attnum > 0 AND NOT a.attisdropped
            ORDER BY a.attnum
            """).param(table).query((rs, n) -> new PartitionManifest.Column(rs.getString(1), rs.getString(2))).list();
        for (PartitionManifest.Column column : columns) {
            if (!IDENTIFIER.matcher(column.name()).matches() || !TYPE.matcher(column.type()).matches()) {
                throw new IllegalStateException("UNSUPPORTED_COLUMN");
            }
        }
        return columns;
    }

    /** 부모 테이블의 기본 키 열을 키 순서대로 낸다. 기본 키가 없으면 실패한다. */
    public List<String> primaryKey(String table) {
        requireTable(table);
        List<String> keys = jdbc.sql("""
            SELECT a.attname
            FROM pg_index i
            CROSS JOIN LATERAL unnest(i.indkey) WITH ORDINALITY AS k(attnum, position)
            JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum
            WHERE i.indrelid = to_regclass(?) AND i.indisprimary
            ORDER BY k.position
            """).param(table).query(String.class).list();
        if (keys.isEmpty()) {
            throw new IllegalStateException("PRIMARY_KEY_REQUIRED");
        }
        return keys;
    }

    /**
     * 파티션을 기본 키 순서로 {@code cursor} 뒤에서 {@code limit} 줄 읽는다. 첫 페이지는 {@code cursor} 가 {@code null} 이다.
     *
     * <p>읽기 전용 트랜잭션 하나로 읽고 페이지마다 끝낸다. {@code extra_float_digits=1} 로 float8 이 왕복이 보장되는 최단 표기로
     * 나오게 하고, 시간대를 UTC 로 고정한다.
     */
    public Page page(
        Partition partition,
        List<PartitionManifest.Column> columns,
        List<String> keys,
        String cursor,
        final int limit
    ) {
        String order = String.join(",", keys.stream().map(k -> "t." + k).toList());
        StringBuilder sql = new StringBuilder("SELECT to_jsonb(t)::text, jsonb_build_array(" + order + ")::text FROM ")
            .append(partition.name()).append(" t");
        if (cursor != null) {
            List<String> bounds = IntStream.range(0, keys.size())
                .mapToObj(i -> "(CAST(:cursor AS jsonb)->>" + i + ")::" + typeOf(columns, keys.get(i)))
                .toList();
            sql.append(" WHERE (").append(order).append(")>(").append(String.join(",", bounds)).append(")");
        }
        sql.append(" ORDER BY ").append(order).append(" LIMIT :limit");
        return readOnly.execute(status -> {
            readLimits();
            JdbcClient.StatementSpec statement = jdbc.sql(sql.toString()).param("limit", limit);
            if (cursor != null) {
                statement = statement.param("cursor", cursor);
            }
            List<String[]> rows = statement.query((rs, n) -> new String[] { rs.getString(1), rs.getString(2) }).list();
            return new Page(rows.stream().map(r -> r[0]).toList(), nextCursor(rows, cursor));
        });
    }

    private static String nextCursor(List<String[]> rows, String cursor) {
        if (rows.isEmpty()) {
            return cursor;
        }
        return rows.getLast()[1];
    }

    private static String typeOf(List<PartitionManifest.Column> columns, String key) {
        return columns.stream()
            .filter(c -> c.name().equals(key))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("PRIMARY_KEY_COLUMN_MISSING"))
            .type();
    }

    private void readLimits() {
        jdbc.sql("SET LOCAL statement_timeout='" + READ_TIMEOUT.toMillis() + "ms'").update();
        jdbc.sql("SET LOCAL extra_float_digits=1").update();
        jdbc.sql("SET LOCAL max_parallel_workers_per_gather=0").update();
        jdbc.sql("SET LOCAL TIME ZONE 'UTC'").update();
    }

    /** 테이블의 기본 파티션 줄 수다. 기본 파티션이 없으면 0 이다. */
    public long defaultRows(String table) {
        requireTable(table);
        return readOnly.execute(status -> {
            readLimits();
            if (!hasDefaultPartition(table)) {
                return 0L;
            }
            return jdbc.sql("SELECT count(*) FROM " + table + "_default").query(Long.class).single();
        });
    }

    private boolean hasDefaultPartition(String table) {
        return jdbc.sql("SELECT to_regclass(?) IS NOT NULL").param(table + "_default").query(Boolean.class).single();
    }

    /** 파티션이 지금 부모 테이블에 붙어 있는지 본다. */
    public boolean attached(Partition partition) {
        return jdbc.sql("""
            SELECT EXISTS (SELECT 1 FROM pg_inherits WHERE inhrelid = to_regclass(?) AND inhparent = to_regclass(?))
            """).params(partition.name(), partition.table()).query(Boolean.class).single();
    }

    /** 파티션의 지금 줄 수다. 잠금 없이 센다. */
    public long count(Partition partition) {
        return readOnly.execute(status -> {
            readLimits();
            return jdbc.sql("SELECT count(*) FROM " + partition.name()).query(Long.class).single();
        });
    }

    public Optional<Ledger> ledger(Partition partition) {
        return jdbc.sql("""
            SELECT object_key, row_count, sha256, exported_at, verified_at, dropped_at
            FROM daily_export WHERE table_name = ? AND source_date = ? AND segment = 0
            """).params(partition.table(), partition.date()).query(LEDGER)
            .optional();
    }

    private static Instant instant(OffsetDateTime time) {
        if (time == null) {
            return null;
        }
        return time.toInstant();
    }

    private static OffsetDateTime time(Instant instant) {
        if (instant == null) {
            return null;
        }
        return instant.atOffset(ZoneOffset.UTC);
    }

    /**
     * 확인을 마친 내보내기를 장부에 적는다. 같은 (테이블, 날짜) 줄이 이미 있으면 적지 않고 그 줄을 낸다.
     *
     * <p>파티션마다 트랜잭션 advisory lock 을 잡고 적으므로 동시에 적는 두 실행 가운데 하나만 적는다.
     */
    public LedgerWrite recordVerified(Partition partition, Ledger ledger) {
        return write.execute(status -> {
            writeLimits(lockTimeout);
            jdbc.sql("SELECT pg_advisory_xact_lock(?, hashtext(?))").params(ADVISORY_NAMESPACE, partition.name())
                .query(Object.class).optional();
            Optional<Ledger> existing = ledger(partition);
            if (existing.isPresent()) {
                return new LedgerWrite(false, existing.get());
            }
            jdbc.sql("""
                INSERT INTO daily_export(table_name, source_date, object_key, row_count, sha256, exported_at, verified_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """).params(partition.table(), partition.date(), ledger.key(), ledger.rows(), ledger.sha256(),
                time(ledger.exportedAt()), time(ledger.verifiedAt())).update();
            return new LedgerWrite(true, ledger);
        });
    }

    /** 확인 시각이 비어 있던 장부 줄에 확인 시각을 적는다. 해시가 다르면 적지 않는다. */
    public boolean markVerified(Partition partition, String sha256, Instant verifiedAt) {
        return write.execute(status -> {
            writeLimits(lockTimeout);
            return jdbc.sql("""
                UPDATE daily_export SET verified_at = ?
                WHERE table_name = ? AND source_date = ? AND segment = 0 AND sha256 = ? AND verified_at IS NULL
                    AND dropped_at IS NULL
                """).params(time(verifiedAt), partition.table(), partition.date(), sha256).update() == 1;
        });
    }

    /**
     * 확인을 마친 파티션을 한 트랜잭션에서 떼어 지우고 장부에 {@code dropped_at} 을 적는다.
     *
     * <p>순서는 다음과 같다. 하나라도 어긋나면 아무것도 바꾸지 않고 그 결과를 낸다.
     * <ol>
     * <li>파티션 advisory lock 을 기다리지 않고 잡는다. 못 잡으면 {@link DropResult#BUSY}.</li>
     * <li>장부 줄을 잠근다. 지운 시각이 있으면 {@link DropResult#ALREADY_DROPPED}. 확인 시각이 있고 줄 수·키·해시가
     * {@code expected} 와 같은지 본다.</li>
     * <li>파티션이 아직 부모에 붙어 있는지 본다.</li>
     * <li>파티션에 {@code SHARE} 잠금을 잡아 새 줄이 들어오지 못하게 한 뒤 줄 수를 세어 장부와 비교한다. 읽기는 막지 않는다.</li>
     * <li>{@code DETACH PARTITION} 과 {@code DROP TABLE} 을 하고 {@code dropped_at} 을 적는다.</li>
     * </ol>
     * 잠금은 {@code lock_timeout} 만큼만 기다리고, 넘기면 {@code 55P03} 예외가 나가며 트랜잭션은 되돌아간다. {@code DETACH} 는 부모와
     * 기본 파티션에 {@code ACCESS EXCLUSIVE} 잠금을 잡으므로 이 잠금은 커밋까지의 짧은 구간에만 쥔다. 기본 파티션이 있으면
     * {@code DETACH CONCURRENTLY} 를 쓸 수 없다.
     */
    public DropResult drop(Partition partition, Ledger expected) {
        return write.execute(status -> {
            writeLimits(lockTimeout);
            if (!jdbc.sql("SELECT pg_try_advisory_xact_lock(?, hashtext(?))")
                .params(ADVISORY_NAMESPACE, partition.name()).query(Boolean.class).single()) {
                return DropResult.BUSY;
            }
            Optional<Ledger> current = lockLedger(partition);
            if (current.isPresent() && current.get().droppedAt() != null) {
                return DropResult.ALREADY_DROPPED;
            }
            if (current.isEmpty() || differsFromVerified(current.get(), expected)) {
                return DropResult.LEDGER_CHANGED;
            }
            if (!attached(partition)) {
                return DropResult.GONE;
            }
            jdbc.sql("LOCK TABLE " + partition.name() + " IN SHARE MODE").update();
            final long rows = jdbc.sql("SELECT count(*) FROM " + partition.name()).query(Long.class).single();
            if (rows != expected.rows()) {
                status.setRollbackOnly();
                return DropResult.ROW_COUNT_CHANGED;
            }
            detachAndDrop(partition);
            return DropResult.DROPPED;
        });
    }

    private Optional<Ledger> lockLedger(Partition partition) {
        return jdbc.sql("""
            SELECT object_key, row_count, sha256, exported_at, verified_at, dropped_at
            FROM daily_export WHERE table_name = ? AND source_date = ? AND segment = 0 FOR UPDATE
            """).params(partition.table(), partition.date()).query(LEDGER)
            .optional();
    }

    /** 확인 시각이 없거나, 지운 시각이 있거나, 줄 수·해시·키가 {@code expected} 와 다르면 참이다. */
    private static boolean differsFromVerified(Ledger current, Ledger expected) {
        return current.verifiedAt() == null || current.droppedAt() != null || current.rows() != expected.rows()
            || !current.sha256().equals(expected.sha256()) || !current.key().equals(expected.key());
    }

    private void detachAndDrop(Partition partition) {
        jdbc.sql("ALTER TABLE " + partition.table() + " DETACH PARTITION " + partition.name()).update();
        jdbc.sql("DROP TABLE " + partition.name()).update();
        jdbc.sql("UPDATE daily_export SET dropped_at = clock_timestamp()"
            + " WHERE table_name = ? AND source_date = ? AND segment = 0")
            .params(partition.table(), partition.date()).update();
    }

    private void writeLimits(Duration lockWait) {
        jdbc.sql("SET LOCAL statement_timeout='" + DROP_STATEMENT_TIMEOUT.toMillis() + "ms'").update();
        jdbc.sql("SET LOCAL lock_timeout='" + lockWait.toMillis() + "ms'").update();
        jdbc.sql("SET LOCAL TIME ZONE 'UTC'").update();
    }

    private static void requireTable(String table) {
        if (!TABLES.contains(table)) {
            throw new IllegalArgumentException("날짜 내보내기 대상이 아닌 테이블이다: " + table);
        }
    }
}
