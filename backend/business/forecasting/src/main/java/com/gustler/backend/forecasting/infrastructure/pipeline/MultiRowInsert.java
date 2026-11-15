package com.gustler.backend.forecasting.infrastructure.pipeline;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.SqlArrayValue;

/**
 * 여러 줄을 {@code INSERT ... VALUES (...), (...) ON CONFLICT DO NOTHING} 한 문장으로 넣는다.
 *
 * <p>PostgreSQL JDBC의 바인딩 변수 상한(65,535개)을 넘지 않도록 {@link #ROWS_PER_STATEMENT}줄씩 나눠 보낸다. 한 묶음이 그보다
 * 작으면 문장 하나다. 바인딩 값은 시각을 UTC {@link OffsetDateTime}으로, 배열을 {@link SqlArrayValue}로 바꿔 넘긴다.
 */
final class MultiRowInsert {
    /** 한 문장에 넣는 최대 줄 수. 열이 가장 많은 {@code stop_pass}(22열)에서도 바인딩 변수가 22,000개다. */
    static final int ROWS_PER_STATEMENT = 1_000;

    private final String head;
    private final String placeholders;

    /**
     * @param table 넣을 테이블
     * @param columns 넣을 열. 행 값은 이 순서를 따른다
     */
    MultiRowInsert(String table, List<String> columns) {
        this.head = "INSERT /* salmonbus:%s.insert */ INTO %s (%s) VALUES ".formatted(table, table,
            String.join(", ", columns));
        this.placeholders = "(" + String.join(", ", columns.stream().map(column -> "?").toList()) + ")";
    }

    /**
     * 행을 넣고 새로 들어간 줄 수를 돌려준다. 이미 있는 키는 건너뛰고 세지 않는다.
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
