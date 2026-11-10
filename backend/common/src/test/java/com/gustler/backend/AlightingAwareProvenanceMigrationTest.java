package com.gustler.backend;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.support.TestDatabase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AlightingAwareProvenanceMigrationTest {
    @ParameterizedTest
    @CsvSource({ "false,UNVERIFIED", "true,REPLAY" })
    void 출처_열이_없던_DB와_먼저_만들어진_DB의_과거_값을_보존한다(
        final boolean existingColumn,
        String expected) throws Exception {
        try (TestDatabase database = TestDatabase.create();
            Connection connection = database.connection();
            Statement statement = connection.createStatement()) {
            // given
            Flyway.configure().dataSource(database.jdbcUrl(), database.username(), database.password())
                .target("48").load().migrate();
            if (existingColumn) {
                statement
                    .execute("ALTER TABLE observation_batch ADD COLUMN ingestion_origin varchar(40) DEFAULT 'REPLAY'");
            }
            statement.execute(
                """
                    WITH r AS (
                      INSERT INTO route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name)
                      VALUES('test','GBIS','test','test','A','B') RETURNING id
                    ), v AS (
                      INSERT INTO route_version(route_id,content_digest,valid_from)
                      SELECT id,repeat('0',64),now() FROM r RETURNING id
                    ) INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,requested_at,
                        outcome,stored_rows,normalization_version,collection_strategy_version)
                      SELECT id,now(),1,'before',now(),'SUCCESS_EMPTY',0,'test','test' FROM v
                    """);

            // when
            Flyway.configure().dataSource(database.jdbcUrl(), database.username(), database.password()).load()
                .migrate();
            statement.execute("""
                INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,requested_at,
                    outcome,stored_rows,normalization_version,collection_strategy_version)
                  SELECT route_version_id,now(),1,'after',now(),'SUCCESS_EMPTY',0,'test','test' FROM observation_batch
                """);

            // then
            try (ResultSet rows = statement
                .executeQuery("SELECT attempt_key,ingestion_origin FROM observation_batch ORDER BY id")) {
                rows.next();
                String oldOrigin = rows.getString("ingestion_origin");
                rows.next();
                String defaultOrigin = rows.getString("ingestion_origin");
                assertSoftly(softly -> {
                    softly.assertThat(oldOrigin).isEqualTo(expected);
                    softly.assertThat(defaultOrigin).isEqualTo("UNVERIFIED");
                });
            }
        }
    }
}
