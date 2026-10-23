package com.gustler.localdata;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

class CatalogRestartTest {
    @Test
    void 추가_노선의_관측을_보존한_채_재검증하고_정류장_변조는_거절한다() throws Exception {
        // given
        var catalog = RouteDataset.readCatalog(Path.of(System.getProperty("dev.data.directory"), "catalog-routes.json"));
        var route = catalog.routes().stream().filter(item -> item.routeId().equals("234000879")).findFirst().orElseThrow();
        var dataset = new RouteDataset(catalog.version(), List.of(route));
        try (var database = new PostgreSQLContainer("postgres:18-alpine")) {
            database.start();
            Flyway.configure().dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .locations("classpath:db/migration").load().migrate();
            try (var connection = DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword())) {
                long version = LocalData.seedRoute(connection, route);
                try (var insert = connection.prepareStatement("""
                    INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,requested_at,
                        response_received_at,outcome,normalization_version,collection_strategy_version)
                    VALUES (?,now(),1,'restart-test',now(),now(),'SUCCESS_ROWS','test','test')
                    """)) {
                    insert.setLong(1, version);
                    insert.executeUpdate();
                }

                // when
                LocalData.verify(connection, dataset, "unused", false, false);
                LocalData.verify(connection, dataset, "unused", false, false);

                // then
                try (var statement = connection.createStatement()) {
                    try (var rows = statement.executeQuery("SELECT count(*) FROM observation_batch")) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getInt(1)).isEqualTo(1);
                    }
                    statement.executeUpdate("UPDATE route_stop SET name='changed' WHERE stop_order=1");
                    assertThatThrownBy(() -> LocalData.verify(connection, dataset, "unused", false, false))
                        .hasMessage("STOP_DATA_CHANGED");
                }
            }
        }
    }
}
