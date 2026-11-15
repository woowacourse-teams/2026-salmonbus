package com.gustler.backend.archive;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 옛 테이블 내보내기가 쓰는 카탈로그 조회, 기본 키 순서 페이지 읽기, 키 범위 줄 세기, 장부({@code daily_export}) 조각 쓰기를 맡는다.
 * 줄을 지우거나 바꾸는 문장은 없다.
 *
 * <p>테이블 이름은 생성자가 받은 목록에서만, 열 이름과 타입 표기는 카탈로그에서 허용 문자만 받아 SQL 에 그대로 넣는다. 값과 커서는
 * 언제나 bind 한다. 장부에는 {@link #SOURCE_DATE} 날짜로 조각마다 한 줄을 쓴다.
 */
public class LegacyExportRepository {
    /**
     * 내보내는 옛 테이블이다. {@code deploy/sql/sal434-03-drop-legacy-tables.sql} 의 {@code target_tables} 와 같은 목록, 같은
     * 순서이며 시험이 두 목록을 대조한다.
     */
    public static final List<String> TABLES = List.of("alighting_settlement_pass", "alighting_settlement_observation",
        "alighting_settlement_projection", "alighting_settlement_batch_work", "alighting_settlement_generation",
        "alighting_settlement_queue_scan", "alighting_forecast_evaluation_result", "alighting_evaluation_archive",
        "alighting_forecast_evaluation_work", "forecast_evaluation_result", "forecast_evaluation_compact",
        "forecast_evaluation_work", "archive_legacy_member", "archive_legacy_batch", "alighting_history_checkpoint",
        "seat_forecast", "forecast_publication", "stop_demand_statistics", "file_statistics_publication",
        "demand_statistics_version", "model_training_exclusion", "statistics_training_exclusion",
        "operational_archive_file", "operational_archive_job", "operational_archive_boundary");

    /** 옛 테이블 조각의 장부 날짜다. 관측 테이블 파티션 전환 SQL 이 옛 줄에 쓰는 날짜와 같다. */
    public static final LocalDate SOURCE_DATE = LocalDate.of(2000, 1, 1);

    /**
     * {@code to_jsonb} 를 거쳐 {@code jsonb_populate_record} 로 같은 값이 돌아오는 것을 시험으로 확인한 타입이다. 배열은 원소
     * 타입으로 판단하되 {@code jsonb[]} 는 받지 않는다(원소의 JSON {@code null} 과 SQL NULL 이 같은 표기가 된다). {@code json} 은
     * 공백·키 순서·중복 키가 바뀌므로 넣지 않는다. {@code jsonb} 열의 JSON {@code null} 은 {@link #JSONB_NULL} 표시로 구분한다.
     */
    static final Set<String> SUPPORTED_TYPES = Set.of("bigint", "integer", "smallint", "boolean", "text",
        "character varying", "character", "uuid", "date", "timestamp with time zone", "timestamp without time zone",
        "double precision", "real", "numeric", "jsonb", "bytea", "xid8");

    /**
     * 줄에 더하는 표시 키다. 값이 JSON {@code null} 인(SQL NULL 이 아닌) {@code jsonb} 열 이름의 배열이며, 그런 열이 없으면 키가 없다.
     * {@code to_jsonb(row)} 는 두 값을 똑같이 {@code null} 로 쓰므로 이 표시가 없으면 되살릴 때 SQL NULL 로 바뀐다. {@code $} 는 열
     * 이름에 올 수 없어 열과 겹치지 않고, {@code jsonb_populate_record} 는 이 키를 무시한다.
     */
    public static final String JSONB_NULL = "$jsonb_null";

    static final int ADVISORY_NAMESPACE = 20261116;
    private static final Duration LEDGER_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(5);
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]{0,62}");
    private static final Pattern TYPE = Pattern.compile("[a-z][a-z0-9 _]*(\\(\\d+(,\\d+)?\\))?(\\[\\])?");
    private static final Pattern MODIFIER = Pattern.compile("\\(\\d+(,\\d+)?\\)");

    private static final RowMapper<Segment> SEGMENT = (rs, n) -> new Segment(rs.getInt(1), rs.getString(2),
        rs.getString(3), rs.getLong(4), rs.getString(5), rs.getString(6).trim(),
        instant(rs.getObject(7, OffsetDateTime.class)), instant(rs.getObject(8, OffsetDateTime.class)));

    private final JdbcClient jdbc;
    private final List<String> tables;
    private final Duration statementTimeout;
    private final TransactionTemplate readOnly;
    private final TransactionTemplate write;

    /**
     * {@link #TABLES} 를 내보내는 저장소를 만든다.
     *
     * @param statementTimeout 페이지 읽기·줄 세기 문장 하나의 제한 시간
     */
    public LegacyExportRepository(JdbcClient jdbc, PlatformTransactionManager manager, Duration statementTimeout) {
        this(jdbc, manager, statementTimeout, TABLES);
    }

    LegacyExportRepository(
        JdbcClient jdbc,
        PlatformTransactionManager manager,
        Duration statementTimeout,
        List<String> tables
    ) {
        for (String table : tables) {
            require(IDENTIFIER, table);
        }
        this.jdbc = jdbc;
        this.tables = List.copyOf(tables);
        this.statementTimeout = statementTimeout;
        readOnly = new TransactionTemplate(manager);
        readOnly.setReadOnly(true);
        readOnly.setTimeout((int) statementTimeout.toSeconds() + 15);
        write = new TransactionTemplate(manager);
        write.setTimeout((int) LEDGER_TIMEOUT.toSeconds() + 15);
    }

    /** 장부의 조각 한 줄. 키는 JSON 배열 텍스트이고 {@code key}·{@code sha256} 은 manifest 의 키와 해시다. */
    public record Segment(
        int number,
        String keyFrom,
        String keyTo,
        long rows,
        String key,
        String sha256,
        Instant exportedAt,
        Instant verifiedAt
    ) {
    }

    /** 기본 키 순서 한 페이지. 키는 첫 줄과 마지막 줄의 기본 키를 담은 JSON 배열 텍스트이며 빈 페이지면 {@code null} 이다. */
    public record Page(List<String> rows, String firstKey, String lastKey) {
        public Page {
            rows = List.copyOf(rows);
        }
    }

    /** 조각을 장부에 적은 결과. */
    public enum RecordResult {
        INSERTED,
        /** 장부의 마지막 조각이 기대와 달라 적지 않았다. 다른 실행이 먼저 적었거나 사람이 장부를 고쳤다. */
        LEDGER_CHANGED
    }

    public List<String> tables() {
        return tables;
    }

    public boolean exists(String table) {
        requireTable(table);
        return jdbc.sql("SELECT /* salmonbus:legacy_export.catalog */ to_regclass(?) IS NOT NULL")
            .param("public." + table).query(Boolean.class).single();
    }

    /** 테이블의 열 이름과 타입을 열 순서대로 낸다. 이름이나 타입 표기가 허용 문자가 아니면 실패한다. */
    public List<PartitionManifest.Column> columns(String table) {
        requireTable(table);
        List<PartitionManifest.Column> columns = jdbc.sql("""
            SELECT /* salmonbus:legacy_export.catalog */ a.attname, format_type(a.atttypid, a.atttypmod)
            FROM pg_attribute a
            WHERE a.attrelid = to_regclass(?) AND a.attnum > 0 AND NOT a.attisdropped
            ORDER BY a.attnum
            """).param("public." + table)
            .query((rs, n) -> new PartitionManifest.Column(rs.getString(1), rs.getString(2))).list();
        for (PartitionManifest.Column column : columns) {
            if (!IDENTIFIER.matcher(column.name()).matches() || !TYPE.matcher(column.type()).matches()) {
                throw new IllegalStateException("UNSUPPORTED_COLUMN");
            }
        }
        return columns;
    }

    /** {@link #SUPPORTED_TYPES} 밖의 타입인 열을 {@code 이름:타입} 으로 낸다. 비어 있어야 값 손실 없이 내보낼 수 있다. */
    public static List<String> unsupportedColumns(List<PartitionManifest.Column> columns) {
        List<String> unsupported = new ArrayList<>();
        for (PartitionManifest.Column column : columns) {
            String type = baseType(column.type());
            if (type.equals("jsonb[]") || !SUPPORTED_TYPES.contains(type.replace("[]", ""))) {
                unsupported.add(column.name() + ":" + column.type());
            }
        }
        return unsupported;
    }

    /** 기본 키 열을 키 순서대로 낸다. 기본 키가 없으면 {@code PRIMARY_KEY_REQUIRED} 로 실패한다. */
    public List<String> primaryKey(String table) {
        requireTable(table);
        List<String> keys = jdbc.sql("""
            SELECT /* salmonbus:legacy_export.catalog */ a.attname
            FROM pg_index i
            CROSS JOIN LATERAL unnest(i.indkey) WITH ORDINALITY AS k(attnum, position)
            JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum
            WHERE i.indrelid = to_regclass(?) AND i.indisprimary
            ORDER BY k.position
            """).param("public." + table).query(String.class).list();
        if (keys.isEmpty()) {
            throw new IllegalStateException("PRIMARY_KEY_REQUIRED");
        }
        return keys;
    }

    /** 장부에 적힌 이 테이블의 조각을 번호 순서로 낸다. */
    public List<Segment> segments(String table) {
        requireTable(table);
        return jdbc.sql("""
            SELECT /* salmonbus:daily_export.find_segments */ segment, key_from, key_to, row_count, object_key,
                sha256, exported_at, verified_at
            FROM daily_export WHERE table_name = ? AND source_date = ? ORDER BY segment
            """).params(table, SOURCE_DATE).query(SEGMENT).list();
    }

    /**
     * 기본 키가 {@code after} 보다 큰 줄을 기본 키 순서로 {@code limit} 줄 읽는다. 첫 페이지는 {@code after} 가 {@code null} 이다.
     *
     * <p>읽기 전용 트랜잭션 하나로 읽고 바로 끝내므로 DB 연결은 이 페이지를 읽는 동안에만 쥔다. 기본 키 인덱스를 행 비교로 따라간다.
     * 세션 설정은 {@link #readLimits()} 를 따른다.
     */
    public Page page(
        String table,
        List<PartitionManifest.Column> columns,
        List<String> keys,
        String after,
        final int limit
    ) {
        requireTable(table);
        StringBuilder sql = new StringBuilder("SELECT /* salmonbus:legacy_export.read_page */ " + rowJson(columns)
            + "::text, jsonb_build_array(" + order(keys) + ")::text FROM " + table + " t");
        if (after != null) {
            sql.append(" WHERE ").append(compare(columns, keys, ">", "after"));
        }
        sql.append(" ORDER BY ").append(order(keys)).append(" LIMIT :limit");
        return readOnly.execute(status -> {
            readLimits();
            JdbcClient.StatementSpec statement = jdbc.sql(sql.toString()).param("limit", limit);
            if (after != null) {
                statement = statement.param("after", after);
            }
            List<String[]> rows = statement.query((rs, n) -> new String[] { rs.getString(1), rs.getString(2) }).list();
            if (rows.isEmpty()) {
                return new Page(List.of(), null, null);
            }
            return new Page(rows.stream().map(r -> r[0]).toList(), rows.getFirst()[1], rows.getLast()[1]);
        });
    }

    /**
     * 기본 키가 {@code after} 보다 크고 {@code upTo} 이하인 줄을 센다. 경계가 {@code null} 이면 그쪽은 열려 있다. 조각 하나가
     * 맡는 범위를 다시 세어 장부와 맞춰 보는 데 쓴다.
     */
    public long countRange(
        String table,
        List<PartitionManifest.Column> columns,
        List<String> keys,
        String after,
        String upTo
    ) {
        requireTable(table);
        List<String> conditions = new ArrayList<>();
        if (after != null) {
            conditions.add(compare(columns, keys, ">", "after"));
        }
        if (upTo != null) {
            conditions.add(compare(columns, keys, "<=", "upTo"));
        }
        String sql = "SELECT /* salmonbus:legacy_export.count_range */ count(*) FROM " + table + " t"
            + whereClause(conditions);
        return readOnly.execute(status -> {
            readLimits();
            JdbcClient.StatementSpec statement = jdbc.sql(sql);
            if (after != null) {
                statement = statement.param("after", after);
            }
            if (upTo != null) {
                statement = statement.param("upTo", upTo);
            }
            return statement.query(Long.class).single();
        });
    }

    private static String whereClause(List<String> conditions) {
        if (conditions.isEmpty()) {
            return "";
        }
        return " WHERE " + String.join(" AND ", conditions);
    }

    /**
     * 확인을 마친 조각을 장부에 적는다.
     *
     * <p>테이블마다 트랜잭션 advisory lock 을 잡고, 장부의 조각 번호가 0부터 빈틈없이 이어지며 마지막 조각의 {@code key_to} 가
     * {@code expectedAfter} 와 같고 새 조각 번호가 그다음일 때만 적는다. 그래서 두 실행이 같은 범위를 겹쳐 적거나 범위를 건너뛸 수 없다.
     */
    public RecordResult record(String table, Segment segment, String expectedAfter) {
        requireTable(table);
        return write.execute(status -> {
            jdbc.sql("SET LOCAL statement_timeout='" + LEDGER_TIMEOUT.toMillis() + "ms'").update();
            jdbc.sql("SET LOCAL lock_timeout='" + LOCK_TIMEOUT.toMillis() + "ms'").update();
            jdbc.sql("SET LOCAL TIME ZONE 'UTC'").update();
            jdbc.sql("SELECT pg_advisory_xact_lock(?, hashtext(?))").params(ADVISORY_NAMESPACE, table)
                .query(Object.class).optional();
            List<Segment> current = segments(table);
            for (int i = 0; i < current.size(); i++) {
                if (current.get(i).number() != i) {
                    return RecordResult.LEDGER_CHANGED;
                }
            }
            String lastKey = lastKeyTo(current);
            if (segment.number() != current.size() || !Objects.equals(lastKey, expectedAfter)) {
                return RecordResult.LEDGER_CHANGED;
            }
            jdbc.sql("""
                INSERT /* salmonbus:daily_export.insert_segment */ INTO daily_export(
                    table_name, source_date, segment, object_key, row_count, sha256, exported_at,
                    verified_at, key_from, key_to)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """).params(table, SOURCE_DATE, segment.number(), segment.key(), segment.rows(), segment.sha256(),
                time(segment.exportedAt()), time(segment.verifiedAt()), segment.keyFrom(), segment.keyTo()).update();
            return RecordResult.INSERTED;
        });
    }

    private static String lastKeyTo(List<Segment> segments) {
        if (segments.isEmpty()) {
            return null;
        }
        return segments.getLast().keyTo();
    }

    /**
     * 읽기 문장의 세션 설정이다. 날짜 파티션 내보내기와 같게 {@code extra_float_digits=1}(float 의 왕복 최단 표기), 시간대 UTC,
     * 병렬 읽기 끔을 두고, {@code bytea} 가 언제나 {@code \x} 16진 표기로 나오게 {@code bytea_output} 을 고정한다.
     */
    private void readLimits() {
        jdbc.sql("SET LOCAL statement_timeout='" + statementTimeout.toMillis() + "ms'").update();
        jdbc.sql("SET LOCAL extra_float_digits=1").update();
        jdbc.sql("SET LOCAL max_parallel_workers_per_gather=0").update();
        jdbc.sql("SET LOCAL TIME ZONE 'UTC'").update();
        jdbc.sql("SET LOCAL bytea_output='hex'").update();
    }

    /**
     * 한 줄의 JSON 이다. {@code to_jsonb(t)} 원문에, 값이 JSON {@code null} 인 {@code jsonb} 열이 있을 때만 {@link #JSONB_NULL} 키를
     * 더한다.
     */
    static String rowJson(List<PartitionManifest.Column> columns) {
        List<String> jsonb = columns.stream()
            .filter(c -> c.type().equals("jsonb"))
            .map(c -> "t." + c.name())
            .toList();
        if (jsonb.isEmpty()) {
            return "to_jsonb(t)";
        }
        String any = String.join(" OR ", jsonb.stream().map(c -> c + "='null'::jsonb").toList());
        String names = String.join(",", jsonb.stream()
            .map(c -> "CASE WHEN " + c + "='null'::jsonb THEN '" + c.substring(2) + "' END").toList());
        return "(CASE WHEN " + any + " THEN to_jsonb(t)||jsonb_build_object('" + JSONB_NULL
            + "',array_to_json(array_remove(ARRAY[" + names + "],NULL))) ELSE to_jsonb(t) END)";
    }

    private static String order(List<String> keys) {
        return String.join(",", keys.stream().map(k -> "t." + k).toList());
    }

    private static String compare(
        List<PartitionManifest.Column> columns,
        List<String> keys,
        String operator,
        String parameter
    ) {
        List<String> bounds = IntStream.range(0, keys.size())
            .mapToObj(i -> "(CAST(:" + parameter + " AS jsonb)->>" + i + ")::" + keyType(columns, keys.get(i)))
            .toList();
        return "(" + order(keys) + ")" + operator + "(" + String.join(",", bounds) + ")";
    }

    private static String keyType(List<PartitionManifest.Column> columns, String key) {
        String type = columns.stream()
            .filter(c -> c.name().equals(key))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("PRIMARY_KEY_COLUMN_MISSING"))
            .type();
        return baseType(type);
    }

    private static String baseType(String type) {
        return MODIFIER.matcher(type).replaceAll("");
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

    private void requireTable(String table) {
        if (!tables.contains(table)) {
            throw new IllegalArgumentException("옛 테이블 내보내기 대상이 아닌 테이블이다: " + table);
        }
    }

    private static String require(Pattern pattern, String value) {
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(value);
        }
        return value;
    }
}
