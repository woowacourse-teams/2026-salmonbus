package com.gustler.backend.worker.integration.forecasting;

import java.util.List;
import org.flywaydb.core.Flyway;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 관측 두 테이블({@code observation_batch}, {@code vehicle_observation})을 일반 테이블로 둔 DB를 만든다.
 *
 * <p>두 테이블을 날짜 파티션 테이블로 다시 만드는 마이그레이션은 줄이 없을 때만 일한다. 그래서 그 앞 버전까지 올린 뒤 배치 한 줄을 넣고
 * 나머지를 올린다. 넣은 줄은 마이그레이션이 끝난 뒤 지워 시험이 빈 테이블에서 시작하게 한다.
 */
@TestConfiguration(proxyBeanMethods = false)
class UnpartitionedObservationTables {
    /** 줄이 없는 관측 테이블을 파티션 테이블로 다시 만드는 마이그레이션 바로 앞 버전이다. */
    private static final String BEFORE_PARTITIONING = "83";
    private static final String SEED = "unpartitioned-seed";
    private static final List<String> INSERT_SEED = List.of("""
        INSERT INTO route (public_route_id, source_id, source_route_id, display_name, start_stop_name, end_stop_name)
        VALUES (:seed, 'GBIS', :seed, :seed, '출발', '도착')
        """, """
        INSERT INTO route_version (route_id, content_digest, valid_from)
        SELECT id, repeat('0', 64), now() FROM route WHERE public_route_id = :seed
        """, """
        INSERT INTO observation_batch (route_version_id, scheduled_at, attempt_number, attempt_key, outcome,
            normalization_version, collection_strategy_version)
        SELECT version.id, now(), 1, :seed, 'RESERVED', 'n', 'c'
        FROM route_version version JOIN route ON route.id = version.route_id WHERE route.public_route_id = :seed
        """);
    private static final List<String> DELETE_SEED = List.of(
        "DELETE FROM observation_batch WHERE attempt_key = :seed",
        "DELETE FROM route_version WHERE route_id IN (SELECT id FROM route WHERE public_route_id = :seed)",
        "DELETE FROM route WHERE public_route_id = :seed");

    @Bean
    FlywayMigrationStrategy migrateKeepingObservationTablesUnpartitioned() {
        return flyway -> {
            JdbcClient jdbc = JdbcClient.create(flyway.getConfiguration().getDataSource());
            Flyway.configure().configuration(flyway.getConfiguration()).target(BEFORE_PARTITIONING).load().migrate();
            INSERT_SEED.forEach(statement -> jdbc.sql(statement).param("seed", SEED).update());
            flyway.migrate();
            DELETE_SEED.forEach(statement -> jdbc.sql(statement).param("seed", SEED).update());
        };
    }
}
