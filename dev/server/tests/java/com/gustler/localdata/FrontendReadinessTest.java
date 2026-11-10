package com.gustler.localdata;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

class FrontendReadinessTest {
    @Test
    void 초기화용_번들과_달라도_현재_활성_모델의_예보가_준비되면_프론트를_시작한다() throws Exception {
        // given: 실제 대기 스크립트의 SQL을 작은 DB로 검증한다.
        String query = Files.readString(Path.of(System.getProperty("dev.data.directory"))
            .resolveSibling("docker").resolve("forecast-ready.sql"));
        try (var database = new PostgreSQLContainer("postgres:18-alpine").withCreateContainerCmdModifier(command ->
            command.getHostConfig().withMemory(384L*1024*1024).withNanoCPUs(1000000000L))) {
            database.start();
            try (var connection = DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword());
                 var sql = connection.createStatement()) {
                sql.execute("""
                    CREATE TABLE route(id bigint PRIMARY KEY, source_route_id text);
                    CREATE TABLE route_version(id bigint PRIMARY KEY, route_id bigint, valid_to timestamptz);

                    CREATE TABLE model_deployment(id bigint PRIMARY KEY, state text, bundle_digest text);
                    CREATE TABLE local_development_fixture(singleton boolean, bundle_digest text);
                    CREATE TABLE forecast_publication(route_version_id bigint, model_deployment_id bigint,
                        provenance text, prediction_count integer);
                    INSERT INTO route_version SELECT n,n,NULL FROM generate_series(1,20) n;
                    INSERT INTO route VALUES (1,'204000057'),(2,'234000050'),(3,'204000070'),(4,'234000886'),(5,'233000392'),(6,'227000038'),(7,'228000184'),(8,'234000051');
                    INSERT INTO local_development_fixture VALUES(true,'reference-eight');
                    INSERT INTO model_deployment VALUES(1,'RETIRED','reference-eight'),(2,'ACTIVE','candidate-twenty');
                    INSERT INTO forecast_publication SELECT n,1,'RECORDED',12 FROM generate_series(1,20) n;
                    """);

                // when / then: 이전 모델의 예보만으로는 통과하지 않는다.
                try (var result = sql.executeQuery(query)) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getBoolean(1)).isFalse();
                }
                sql.execute("INSERT INTO forecast_publication SELECT n,2,'RECORDED',12 FROM generate_series(1,7) n");
                try (var result = sql.executeQuery(query)) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getBoolean(1)).isFalse();
                }
                sql.execute("INSERT INTO forecast_publication VALUES(8,2,'RECORDED',12)");
                try (var result = sql.executeQuery(query)) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getBoolean(1)).isTrue();
                }
                sql.execute("UPDATE model_deployment SET state='RETIRED'");
                try (var result = sql.executeQuery(query)) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getBoolean(1)).isFalse();
                }
            }
        }
    }
}
