package com.gustler.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.support.IntegrationTest;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class DatabaseConnectionTest {

    private static final int PRODUCTION_MAJOR_VERSION = 18;

    @Autowired
    private DataSource dataSource;

    @Test
    void 테스트는_운영과_같은_PostgreSQL_18에서_돈다() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            // when
            final DatabaseMetaData actual = connection.getMetaData();

            // then
            assertThat(actual.getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(actual.getDatabaseMajorVersion()).isEqualTo(PRODUCTION_MAJOR_VERSION);
        }
    }
}
