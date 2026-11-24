package com.gustler.backend.forecasting.infrastructure.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.SqlArrayValue;

/**
 * 여러 행을 {@code INSERT ... VALUES (...), (...) ON CONFLICT DO NOTHING} 한 문장으로 넣는다.
 *
 * <p>PostgreSQL JDBC의 바인딩 변수 상한(65,535개)을 넘지 않도록 {@link #ROWS_PER_STATEMENT}행씩 나눠 보낸다. 한 묶음이 그보다
 * 작으면 문장 하나다. 바인딩 값은 시각을 UTC {@link OffsetDateTime}으로, 배열을 {@link SqlArrayValue}로 바꿔 넘긴다.
 */
final class MultiRowInsert {

    /** 한 문장에 넣는 최대 행 수. 열이 가장 많은 {@code stop_pass}(22열)에서도 바인딩 변수가 22,000개다. */
    static final int ROWS_PER_STATEMENT = 1_000;

    private final List<String> columns;
    private final String head;
    private final String placeholders;

    /**
     * @param table 넣을 테이블
     * @param columns 넣을 열. 행 값은 이 순서를 따른다
     */
    MultiRowInsert(String table, List<String> columns) {
        this.columns = List.copyOf(columns);
        this.head = "INSERT /* salmonbus:%s.insert */ INTO %s (%s) VALUES ".formatted(table, table,
            String.join(", ", columns));
        this.placeholders = "(" + String.join(", ", columns.stream().map(column -> "?").toList()) + ")";
    }

    /**
     * 행을 넣고 새로 들어간 행 수를 반환한다. 이미 있는 키는 건너뛰고 세지 않는다.
     */
    <T> int insert(JdbcClient jdbc, List<T> rows, Function<T, List<Object>> values) {
        int inserted = 0;
        for (int start = 0; start < rows.size(); start += ROWS_PER_STATEMENT) {
            List<T> chunk = rows.subList(start, Math.min(rows.size(), start + ROWS_PER_STATEMENT));
            List<Object> parameters = new ArrayList<>();
            for (T row : chunk) {
                parameters.addAll(values.apply(row));
            }
            String sql = head + String.join(", ", Collections.nCopies(chunk.size(), placeholders))
                + " ON CONFLICT DO NOTHING";
            inserted += jdbc.sql(sql).params(parameters).update();
        }
        return inserted;
    }

    /**
     * 행을 넣고 새로 들어간 행만 받은 순서로 반환한다. 이미 있는 키는 건너뛴다. 같은 키가 목록에 둘 이상이면 앞의 행만 반환한다.
     *
     * <p>넣은 줄의 기본 키를 {@code RETURNING}으로 받아 행을 되짚는다. 키 값은 행 값과 같은 자바 타입으로 읽어 견준다.
     *
     * @param keyColumns 기본 키 열. 넣을 열 가운데 있어야 하고 값이 비어 있지 않아야 한다
     */
    <T> List<T> insertNew(
        JdbcClient jdbc,
        List<T> rows,
        Function<T, List<Object>> values,
        List<String> keyColumns
    ) {
        int[] keyIndexes = keyColumns.stream().mapToInt(columns::indexOf).toArray();
        String tail = " ON CONFLICT DO NOTHING RETURNING " + String.join(", ", keyColumns);
        List<T> inserted = new ArrayList<>();
        for (int start = 0; start < rows.size(); start += ROWS_PER_STATEMENT) {
            List<T> chunk = rows.subList(start, Math.min(rows.size(), start + ROWS_PER_STATEMENT));
            List<Object> parameters = new ArrayList<>();
            Map<List<Object>, T> firstByKey = new LinkedHashMap<>();
            for (T row : chunk) {
                List<Object> rowValues = values.apply(row);
                parameters.addAll(rowValues);
                firstByKey.putIfAbsent(keyOf(rowValues, keyIndexes), row);
            }
            List<Object> sample = firstByKey.keySet().iterator().next();
            String sql = head + String.join(", ", Collections.nCopies(chunk.size(), placeholders)) + tail;
            Set<List<Object>> stored = new HashSet<>(jdbc.sql(sql).params(parameters)
                .query((row, index) -> storedKey(row, sample))
                .list());
            firstByKey.forEach((key, row) -> {
                if (stored.contains(key)) {
                    inserted.add(row);
                }
            });
        }
        return inserted;
    }

    private static List<Object> keyOf(List<Object> rowValues, int[] keyIndexes) {
        List<Object> key = new ArrayList<>(keyIndexes.length);
        for (final int index : keyIndexes) {
            key.add(rowValues.get(index));
        }
        return key;
    }

    private static List<Object> storedKey(ResultSet row, List<Object> sample) throws SQLException {
        List<Object> key = new ArrayList<>(sample.size());
        for (int index = 0; index < sample.size(); index++) {
            key.add(row.getObject(index + 1, sample.get(index).getClass()));
        }
        return key;
    }

    static OffsetDateTime timestamp(Instant instant) {
        if (instant == null) {
            return null;
        }
        return instant.atOffset(ZoneOffset.UTC);
    }

    static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        if (value == null) {
            return null;
        }
        return value.toInstant();
    }

    static SqlArrayValue integers(int[] values) {
        return new SqlArrayValue("int4", (Object[]) Arrays.stream(values).boxed().toArray(Integer[]::new));
    }

    static SqlArrayValue doubles(double[] values) {
        return new SqlArrayValue("float8", (Object[]) Arrays.stream(values).boxed().toArray(Double[]::new));
    }

    static int[] integers(ResultSet row, String column) throws SQLException {
        Object[] values = (Object[]) row.getArray(column).getArray();
        return Arrays.stream(values).mapToInt(value -> ((Number) value).intValue()).toArray();
    }

    static double[] doubles(ResultSet row, String column) throws SQLException {
        Object[] values = (Object[]) row.getArray(column).getArray();
        return Arrays.stream(values).mapToDouble(value -> ((Number) value).doubleValue()).toArray();
    }

    static Long nullableLong(ResultSet row, String column) throws SQLException {
        final long value = row.getLong(column);
        if (row.wasNull()) {
            return null;
        }
        return value;
    }
}
