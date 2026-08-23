package com.gustler.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.support.IntegrationTest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@IntegrationTest
class DatabaseConnectionTest {

    private static final Instant NINE_AM_IN_SEOUL = Instant.parse("2026-08-20T00:00:00Z");
    private static final LocalDateTime SEOUL_WALL_CLOCK = LocalDateTime.of(2026, 8, 20, 9, 0);

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void PostgreSQL_에_연결된다() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            final String actual = connection.getMetaData().getDatabaseProductName();

            assertThat(actual).isEqualTo("PostgreSQL");
        }
    }

    @Test
    void 넣은_순간이_JVM_시간대와_무관하게_그대로_돌아온다() {
        final OffsetDateTime actual = jdbcClient.sql("SELECT CAST(? AS timestamptz)")
            .param(NINE_AM_IN_SEOUL.atOffset(ZoneOffset.UTC))
            .query(OffsetDateTime.class)
            .single();

        assertThat(actual.toInstant()).isEqualTo(NINE_AM_IN_SEOUL);
    }

    @Test
    void 같은_순간을_서울_벽시계로_읽으면_오전_아홉시다() {
        final LocalDateTime actual = jdbcClient.sql("SELECT CAST(? AS timestamptz) AT TIME ZONE 'Asia/Seoul'")
            .param(NINE_AM_IN_SEOUL.atOffset(ZoneOffset.UTC))
            .query(LocalDateTime.class)
            .single();

        assertThat(actual).isEqualTo(SEOUL_WALL_CLOCK);
    }
}
