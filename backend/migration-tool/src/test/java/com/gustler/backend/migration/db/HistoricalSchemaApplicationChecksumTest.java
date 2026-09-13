package com.gustler.backend.migration.db;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.migration.MigrationException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import org.junit.jupiter.api.Test;

class HistoricalSchemaApplicationChecksumTest extends PostgresMigrationTestSupport {

    @Test
    void rejectsAnApplicationMigrationChecksumThatNoLongerMatchesV1ThroughV12() throws Exception {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            UPDATE flyway_schema_history SET checksum=checksum+1 WHERE version='12'
            """)) {
            statement.executeUpdate();
        }

        assertThatThrownBy(() -> new HistoricalSchema().migrate(database))
            .isInstanceOf(MigrationException.class)
            .hasMessage("APPLICATION_FLYWAY_BASELINE_MISMATCH");
    }
}
