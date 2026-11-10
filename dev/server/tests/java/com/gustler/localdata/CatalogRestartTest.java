package com.gustler.localdata;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

class CatalogRestartTest {
    @Test
    void 지문_변경_마이그레이션_뒤에도_기존_관측과_초기화_기록을_보존하고_재검증한다() throws Exception {
        // given
        var catalog = RouteDataset.readCatalog(Path.of(System.getProperty("dev.data.directory"), "catalog-routes.json"));
        var route = catalog.routes().stream().filter(item -> item.routeId().equals("234000879")).findFirst().orElseThrow();
        var modelRoute = RouteDataset.read(Path.of(System.getProperty("dev.data.directory"), "routes.json"))
            .routes().getFirst();
        var dataset = new RouteDataset(catalog.version(), List.of(route, modelRoute));
        String fixtureDigest = ReferenceBundle.sha(Files.readAllBytes(
            Path.of(System.getProperty("dev.data.directory"), "routes.json")));
        String catalogDigest = ReferenceBundle.sha(Files.readAllBytes(
            Path.of(System.getProperty("dev.data.directory"), "catalog-routes.json")));
        try (var database = new PostgreSQLContainer("postgres:18-alpine").withCreateContainerCmdModifier(command ->
            command.getHostConfig().withMemory(384L*1024*1024).withNanoCPUs(1000000000L))) {
            database.start();
            Flyway.configure().dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .locations("classpath:db/migration").target("47").load().migrate();
            try (var connection = DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword())) {
                long version = LocalData.seedRoute(connection, route);
                long modelVersion = LocalData.seedRoute(connection, modelRoute);
                try (var update = connection.prepareStatement("UPDATE route_version SET content_digest=? WHERE id=?")) {
                    update.setString(1, route.contentDigest());
                    update.setLong(2, version);
                    update.executeUpdate();
                    update.setString(1, modelRoute.contentDigest());
                    update.setLong(2, modelVersion);
                    update.executeUpdate();
                }
                try (var sql = connection.createStatement()) {
                    sql.execute("CREATE TABLE local_development_fixture(singleton boolean PRIMARY KEY, route_digest char(64))");
                    sql.execute("CREATE TABLE local_development_catalog(singleton boolean PRIMARY KEY, content_digest char(64))");
                }
                try (var insert = connection.prepareStatement("INSERT INTO local_development_fixture VALUES(true,?)")) {
                    insert.setString(1, fixtureDigest);
                    insert.executeUpdate();
                }
                try (var insert = connection.prepareStatement("INSERT INTO local_development_catalog VALUES(true,?)")) {
                    insert.setString(1, catalogDigest);
                    insert.executeUpdate();
                }
                try (var insert = connection.prepareStatement("""
                    INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,requested_at,
                        response_received_at,outcome,normalization_version,collection_strategy_version)
                    VALUES (?,now(),1,'restart-test',now(),now(),'SUCCESS_ROWS','test','test')
                    """)) {
                    insert.setLong(1, version);
                    insert.executeUpdate();
                }

                // when
                Flyway.configure().dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                    .locations("classpath:db/migration").load().migrate();
                LocalData.verify(connection, dataset, "unused", false);
                LocalData.verify(connection, dataset, "unused", false);

                // then
                try (var statement = connection.createStatement()) {
                    try (var rows = statement.executeQuery("SELECT count(*) FROM observation_batch")) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getInt(1)).isEqualTo(1);
                    }
                    try (var rows = statement.executeQuery("SELECT id FROM route_version ORDER BY id")) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getLong(1)).isEqualTo(version);
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getLong(1)).isEqualTo(modelVersion);
                        assertThat(rows.next()).isFalse();
                    }
                    try (var rows = statement.executeQuery("SELECT route_digest FROM local_development_fixture")) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getString(1)).isEqualTo(fixtureDigest);
                    }
                    try (var rows = statement.executeQuery("SELECT content_digest FROM local_development_catalog")) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getString(1)).isEqualTo(catalogDigest);
                    }
                    statement.executeUpdate("UPDATE route_stop SET name='changed' WHERE stop_order=1");
                    assertThatThrownBy(() -> LocalData.verify(connection, dataset, "unused", false))
                        .hasMessage("STOP_DATA_CHANGED");
                }
            }
        }
    }
}
