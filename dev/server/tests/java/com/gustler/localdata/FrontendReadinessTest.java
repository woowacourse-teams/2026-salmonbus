package com.gustler.localdata;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.support.TestDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 프론트 시작을 기다리는 스크립트가 쓰는 SQL({@code dev/docker/forecast-ready.sql})을 작은 DB로 검증한다.
 * 노선 20개 가운데 앞의 8개가 모델 지원 노선이고, 모델 배포 1은 이전 모델, 2는 활성 모델이다.
 */
class FrontendReadinessTest {
    private TestDatabase database;
    private Connection connection;
    private String query;

    @BeforeEach
    void 노선과_모델_배포를_준비한다() throws Exception {
        query = Files.readString(Path.of(System.getProperty("dev.data.directory"))
            .resolveSibling("docker").resolve("forecast-ready.sql"));
        database = TestDatabase.create();
        connection = database.connection();
        execute("""
            CREATE TABLE route(id bigint PRIMARY KEY, source_route_id text);
            CREATE TABLE route_version(id bigint PRIMARY KEY, route_id bigint, valid_to timestamptz);
            CREATE TABLE model_deployment(id bigint PRIMARY KEY, state text, bundle_digest text);
            CREATE TABLE forecast_run(route_version_id bigint, model_deployment_id bigint, outcome text,
                prediction_count integer);
            INSERT INTO route_version SELECT n,n,NULL FROM generate_series(1,20) n;
            INSERT INTO route VALUES (1,'204000057'),(2,'234000050'),(3,'204000070'),(4,'234000886'),
                (5,'233000392'),(6,'227000038'),(7,'228000184'),(8,'234000051');
            INSERT INTO model_deployment VALUES(1,'RETIRED','reference-eight'),(2,'ACTIVE','candidate-twenty');
            """);
    }

    @AfterEach
    void 연결과_DB를_정리한다() throws Exception {
        connection.close();
        database.close();
    }

    @Test
    void 이전_모델의_예보만_있으면_준비되지_않은_것으로_본다() throws Exception {
        // given
        execute("INSERT INTO forecast_run SELECT n,1,'PUBLISHED',12 FROM generate_series(1,20) n");

        // when
        final boolean ready = ready();

        // then
        assertThat(ready).isFalse();
    }

    @Test
    void 활성_모델이_지원_노선_여덟_개를_모두_예보해야_준비된_것으로_본다() throws Exception {
        // given
        execute("INSERT INTO forecast_run SELECT n,2,'PUBLISHED',12 FROM generate_series(1,7) n");

        // when
        final boolean readyWithSeven = ready();
        execute("INSERT INTO forecast_run VALUES(8,2,'PUBLISHED',12)");
        final boolean readyWithEight = ready();

        // then
        assertThat(readyWithSeven).isFalse();
        assertThat(readyWithEight).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"OUT_OF_SCOPE,true", "EMPTY,false", "LATE,false", "INPUT_UNAVAILABLE,false"})
    void 예측을_저장했거나_지원_시간_밖이라_예측이_없는_실행만_준비로_센다(String outcome, final boolean expected)
        throws Exception {
        // given
        execute("INSERT INTO forecast_run SELECT n,2,'PUBLISHED',12 FROM generate_series(1,7) n");
        execute("INSERT INTO forecast_run VALUES(8,2,'" + outcome + "',0)");

        // when
        final boolean ready = ready();

        // then
        assertThat(ready).isEqualTo(expected);
    }

    @Test
    void 활성_모델이_없으면_준비되지_않은_것으로_본다() throws Exception {
        // given
        execute("INSERT INTO forecast_run SELECT n,2,'PUBLISHED',12 FROM generate_series(1,8) n");
        execute("UPDATE model_deployment SET state='RETIRED'");

        // when
        final boolean ready = ready();

        // then
        assertThat(ready).isFalse();
    }

    private boolean ready() throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(query)) {
            assertThat(result.next()).isTrue();
            return result.getBoolean(1);
        }
    }

    private void execute(String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
