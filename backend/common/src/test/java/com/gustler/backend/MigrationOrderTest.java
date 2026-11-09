package com.gustler.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.support.IntegrationTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 마이그레이션 번호와 브랜치 머지 순서가 일치하는지 검사한다.
 *
 * <p>SchemaMigrationTest는 빈 DB에서 전체 파일을 번호 순으로 실행하므로 번호가 머지 순서와
 * 거꾸로 배정되어도 발견하지 못한다. 실제로 이 티켓의 마이그레이션 번호가 궤적 마이그레이션보다
 * 낮아, 궤적이 먼저 머지된 DB에서는 Flyway 기본값(outOfOrder=false)이 낮은 번호를 실행하지 않고
 * 검증에서 차단한 적이 있다.
 *
 * <p>여기서는 이 티켓이 추가하는 파일을 제외한 DB를 먼저 만든 뒤 전체 파일을 다시 적용한다.
 * 선행 브랜치가 머지된 dev에 이 브랜치가 머지되는 순서를 재현한다.
 */
@IntegrationTest
class MigrationOrderTest {
    /**
     * 이 브랜치가 추가하는 마이그레이션이다. 브랜치마다 다르므로 이 목록을 수정한다.
     *
     * <p>번호로 선택하면 안 된다. 가장 높은 번호가 이 브랜치의 것이라고 가정하면,
     * 번호가 거꾸로 배정되어도 다른 파일을 제외해 항상 통과하게 된다. 파일 이름으로 선택하고,
     * 해당 파일이 실제로 마지막 번호인지는 아래에서 별도로 검증한다.
     *
     * <p>이 브랜치는 차량 정원 테이블, 정적 초기값과 좌석 품질 기준을 적용한다.
     */
    private static final List<String> MIGRATIONS_THIS_BRANCH_ADDS =
        List.of("vehicle_capacity", "vehicle_seat_quality",
            "operational_observation_columns", "operational_observation", "retire_trip_quality", "operational_archive",
            "unfinished_collection_recovery");

    private static final String MIGRATION_LOCATION = "db/migration";
    private static final String STAGED_SCHEMA = "staged_deploy";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void 앞_브랜치가_머지된_DB_에_이_브랜치의_마이그레이션이_올라간다() {
        // given
        // 이 브랜치의 파일을 제외하고 먼저 적용한다
        migrateWith("filesystem:" + stagedMigrations());

        // when
        // 전체 파일이 있는 상태로 다시 적용한다
        MigrateResult actual = migrateWith("classpath:" + MIGRATION_LOCATION);

        // then
        assertThat(actual.success).isTrue();
    }

    @Test
    void 앞_브랜치가_머지된_DB_에서도_모든_마이그레이션이_적용된_상태가_된다() {
        // given
        migrateWith("filesystem:" + stagedMigrations());
        migrateWith("classpath:" + MIGRATION_LOCATION);

        // when
        List<Integer> actual = versionsOf(flywayAt("classpath:" + MIGRATION_LOCATION).info().applied());

        // then
        // 누락 없이 모두 적용된 상태다
        assertThat(actual).hasSize(migrationCount());
    }

    @Test
    void 이_브랜치가_더하는_마이그레이션이_마지막_번호를_차지한다() {
        // when
        List<Integer> actual = thisBranchMigrations().stream()
            .map(this::versionOf)
            .toList();

        // then
        // 선행 브랜치가 먼저 머지되므로 이 브랜치의 파일이 마지막 번호여야 한다
        final int firstOfThisBranch = migrationCount() - MIGRATIONS_THIS_BRANCH_ADDS.size() + 1;
        assertThat(actual).containsExactlyElementsOf(
            IntStream.rangeClosed(firstOfThisBranch, migrationCount())
                .boxed()
                .toList());
    }

    @Test
    void 마이그레이션_번호에_빈_자리도_겹치는_자리도_없다() {
        // when
        List<Integer> actual = versionsOf(flywayAt("classpath:" + MIGRATION_LOCATION).info().all());

        // then
        // 1부터 하나씩 증가한다. 번호를 나눠 배정할 때 생긴 누락이나 중복을 검사한다
        assertThat(actual).containsExactly(
            IntStream.rangeClosed(1, actual.size())
                .boxed()
                .toArray(Integer[]::new));
    }

    @Test
    void 조사_완료_후_기존_제외_결과를_옮기고_운영_조회는_조사_테이블을_읽지_않는다() {
        // given
        Flyway.configure().dataSource(dataSource).locations("classpath:" + MIGRATION_LOCATION)
            .schemas(STAGED_SCHEMA).createSchemas(true).target("63").load().migrate();
        jdbcClient.sql("""
            INSERT INTO staged_deploy.route
                (public_route_id, source_id, source_route_id, display_name, start_stop_name, end_stop_name)
            VALUES ('test', 'GBIS', 'test', '3330', '출발', '도착')
            """).update();
        jdbcClient.sql("""
            INSERT INTO staged_deploy.route_version (route_id, content_digest, valid_from)
            SELECT id, repeat('0', 64), CURRENT_TIMESTAMP FROM staged_deploy.route
            """).update();
        final long version = jdbcClient.sql("SELECT id FROM staged_deploy.route_version").query(Long.class).single();
        jdbcClient.sql("""
            INSERT INTO staged_deploy.route_stop (route_version_id, stop_order, stop_id, name, direction, boarding_allowed)
            VALUES (?, 1, 'stop', '출발', 'UP', true)
            """).param(version).update();
        final long batch = jdbcClient.sql("""
            INSERT INTO staged_deploy.observation_batch
                (route_version_id, scheduled_at, attempt_number, attempt_key, requested_at,
                 response_received_at, outcome, normalization_version, collection_strategy_version)
            VALUES (?, CURRENT_TIMESTAMP, 1, 'test', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                'SUCCESS_ROWS', 'test', 'test')
            RETURNING id
            """).param(version).query(Long.class).single();
        jdbcClient.sql("""
            INSERT INTO staged_deploy.vehicle_observation
                (observation_batch_id, route_version_id, source_row_number, vehicle_id,
                 stop_order, stop_id, running_state, remaining_seats, passed_stop_order)
            SELECT ?, ?, n, 'bus-' || n, 1, 'stop', 2, seats, 1
            FROM (VALUES (1, 44), (2, 82), (3, 45)) rows(n, seats)
            """).params(batch, version).update();
        jdbcClient.sql("""
            INSERT INTO staged_deploy.vehicle_one_way_trip
                (id, start_observation_id, route_version_id, vehicle_id, status, boundary, rule_version)
            SELECT 'trip-1', id, route_version_id, vehicle_id, 'EXCLUDED', 'DEPARTURE', 'test'
            FROM staged_deploy.vehicle_observation WHERE source_row_number = 1
            """).update();
        jdbcClient.sql("""
            INSERT INTO staged_deploy.observation_trip_assignment(observation_id, trip_id)
            SELECT start_observation_id, id FROM staged_deploy.vehicle_one_way_trip
            """).update();
        jdbcClient.sql("""
            INSERT INTO staged_deploy.trip_quality_rebuild(route_version_id, vehicle_id, until_at, phase)
            VALUES (?, 'bus-1', CURRENT_TIMESTAMP, 'REPLAY')
            """).param(version).update();

        // when
        assertThatThrownBy(() -> migrateTo64())
            .isInstanceOf(FlywayException.class).hasMessageContaining("Finish pending historical trip");
        jdbcClient.sql("UPDATE staged_deploy.trip_quality_rebuild SET completed = true, phase = 'DONE'").update();
        migrateTo64();
        jdbcClient.sql("UPDATE staged_deploy.vehicle_one_way_trip SET status = 'ELIGIBLE'").update();

        // then
        assertThat(jdbcClient.sql("""
            SELECT historical_exclusion FROM staged_deploy.vehicle_observation ORDER BY source_row_number
            """).query(String.class).list()).containsExactly("CONFIRMED", "INELIGIBLE", null);
        assertThat(jdbcClient.sql("""
            SELECT vehicle_trip_key FROM staged_deploy.operational_observation WHERE source_row_number = 1
            """).query(String.class).single()).isEqualTo("trip-1");
        assertThat(jdbcClient.sql("""
            SELECT remaining_seats FROM staged_deploy.operational_eligible_observation
            """).query(Integer.class).list()).containsExactly(45);
        assertThat(jdbcClient.sql("EXPLAIN (VERBOSE) SELECT * FROM staged_deploy.operational_eligible_observation")
            .query(String.class).list()).noneMatch(line -> line.contains("trip_quality_rebuild")
                || line.contains("observation_trip_assignment") || line.contains("vehicle_one_way_trip"));
        prepareRetirementForecasts(version, batch);
        migrateWith("classpath:" + MIGRATION_LOCATION);
        assertThat(jdbcClient.sql("""
            SELECT data_revision FROM staged_deploy.route
            """).query(Long.class).single()).isEqualTo(7);
        assertThat(jdbcClient.sql("""
            SELECT reported_remaining_seats FROM staged_deploy.vehicle_observation
            WHERE remaining_seats IS NULL ORDER BY source_row_number
            """).query(Integer.class).list()).containsExactly(44, 82, 0);
        assertThat(jdbcClient.sql("""
            SELECT source.source_row_number FROM staged_deploy.forecast_evaluation_pending pending
            JOIN staged_deploy.vehicle_observation source ON source.id = pending.vehicle_observation_id
            """).query(Integer.class).list()).containsExactly(3);
        assertThat(jdbcClient.sql("""
            SELECT scoring_state FROM staged_deploy.forecast_evaluation_result
            """).query(String.class).list()).containsExactly("QUALITY_EXCLUDED");
        assertThat(jdbcClient.sql("""
            SELECT count(*) FROM information_schema.tables
            WHERE table_schema = 'staged_deploy'
              AND table_name IN ('route_data_quality', 'route_version_quality_policy', 'vehicle_one_way_trip',
                  'observation_trip_assignment', 'trip_quality_rebuild')
            """).query(Integer.class).single()).isZero();
        assertThat(jdbcClient.sql("""
            EXPLAIN (VERBOSE) SELECT * FROM staged_deploy.operational_observation
            """).query(String.class).list()).noneMatch(line -> line.contains("historical_exclusion")
                || line.contains("trip_quality") || line.contains("forecast_eligible"));
    }

    private void prepareRetirementForecasts(final long version, final long batch) {
        jdbcClient.sql("""
            INSERT INTO staged_deploy.route_stop
                (route_version_id, stop_order, stop_id, name, direction, boarding_allowed)
            VALUES (?, 2, 'target', '도착', 'UP', true)
            """).param(version).update();
        jdbcClient.sql("""
            INSERT INTO staged_deploy.vehicle_observation
                (observation_batch_id, route_version_id, source_row_number, vehicle_id,
                 stop_order, stop_id, running_state, remaining_seats, passed_stop_order, historical_exclusion)
            VALUES (?, ?, 4, 'bus-4', 1, 'stop', 2, 0, 1, 'CONFIRMED')
            """).params(batch, version).update();
        jdbcClient.sql("""
            INSERT INTO staged_deploy.route_data_quality(route_id, quality_revision)
            SELECT route_id, 7 FROM staged_deploy.route_version WHERE id=?
            """).param(version).update();
        final long model = jdbcClient.sql("""
            INSERT INTO staged_deploy.model_deployment
                (deployment_key, release_id, model_key, model_version, bundle_digest,
                 prediction_target_version, calculation_version, supported_scope_digest, data_until, state)
            VALUES ('00000000-0000-0000-0000-000000000001', 'test', 'test', '1', repeat('0', 64),
                'SEAT_FULL_CHANCE_V1', 'test', repeat('0', 64), CURRENT_TIMESTAMP, 'STAGED')
            RETURNING id
            """).query(Long.class).single();
        final long publication = jdbcClient.sql("""
            INSERT INTO staged_deploy.forecast_publication
                (source_batch_id, route_version_id, model_deployment_id, demand_statistics_revision,
                 quality_revision, observed_at, generated_at, published_at, prediction_count)
            VALUES (?, ?, ?, 1, 7, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 2) RETURNING id
            """).params(batch, version, model).query(Long.class).single();
        jdbcClient.sql("""
            INSERT INTO staged_deploy.seat_forecast
                (vehicle_observation_id, target_stop_order, route_version_id, stops_to_target,
                 model_deployment_id, demand_statistics_revision, quality_revision, seat_full_chance_raw,
                 seat_full_chance, expected_seats, generated_at, publication_id)
            SELECT id, 2, route_version_id, 1, ?, 1, 7, 0.5, 0.5, 20, CURRENT_TIMESTAMP, ?
            FROM staged_deploy.vehicle_observation WHERE source_row_number IN (1, 3)
            """).params(model, publication).update();
        jdbcClient.sql("""
            INSERT INTO staged_deploy.forecast_evaluation_work(vehicle_observation_id, route_version_id)
            SELECT vehicle_observation_id, route_version_id FROM staged_deploy.seat_forecast
            """).update();
    }

    /**
     * 스키마 생성 기록에는 버전이 없으므로 마이그레이션만 센다.
     */
    private List<Integer> versionsOf(
        MigrationInfo[] infos
    ) {
        return Stream.of(infos)
            .filter(info -> info.getVersion() != null)
            .map(info -> Integer.valueOf(info.getVersion().getVersion()))
            .toList();
    }

    private static boolean isThisBranchMigration(
        String fileName
    ) {
        return MIGRATIONS_THIS_BRANCH_ADDS.stream()
            .anyMatch(name -> fileName.endsWith("__" + name + ".sql"));
    }

    /**
     * 이 브랜치가 추가하는 마이그레이션이다. 개수가 다르면 상수가 현재 목록과 맞지 않는 것이다.
     */
    private List<MigrationInfo> thisBranchMigrations() {
        List<MigrationInfo> found = Stream.of(flywayAt("classpath:" + MIGRATION_LOCATION).info().all())
            .filter(info -> isThisBranchMigration(info.getScript()))
            .toList();
        assertThat(found)
            .withFailMessage("이 브랜치가 더하는 마이그레이션 %s 를 %d 개 찾아야 하는데 %d 개다",
                MIGRATIONS_THIS_BRANCH_ADDS, MIGRATIONS_THIS_BRANCH_ADDS.size(), found.size())
            .hasSize(MIGRATIONS_THIS_BRANCH_ADDS.size());
        return found;
    }

    private int versionOf(
        MigrationInfo info
    ) {
        return Integer.parseInt(info.getVersion().getVersion());
    }

    private int migrationCount() {
        return versionsOf(flywayAt("classpath:" + MIGRATION_LOCATION).info().all()).size();
    }

    /**
     * 이 브랜치가 추가하는 파일을 제외한 마이그레이션 묶음이다. 선행 브랜치까지만 머지된 dev와 같은 상태다.
     */
    private Path stagedMigrations() {
        try {
            Path source = Path.of("src/main/resources", MIGRATION_LOCATION);
            Path staged = Files.createTempDirectory("staged-migration");
            try (Stream<Path> files = Files.list(source)) {
                files.filter(file -> !isThisBranchMigration(file.getFileName().toString()))
                    .forEach(file -> copyInto(staged, file));
            }
            return staged;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void copyInto(
        Path staged,
        Path file
    ) {
        try {
            Files.copy(file, staged.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 앱이 쓰는 스키마에 영향을 주지 않도록 별도로 만든 스키마에 적용한다.
     */
    private MigrateResult migrateWith(
        String location
    ) {
        return flywayAt(location).migrate();
    }

    private MigrateResult migrateTo64() {
        return Flyway.configure().dataSource(dataSource).locations("classpath:" + MIGRATION_LOCATION)
            .schemas(STAGED_SCHEMA).createSchemas(true).target("64").load().migrate();
    }

    private Flyway flywayAt(
        String location
    ) {
        return Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .schemas(STAGED_SCHEMA)
            .createSchemas(true)
            .load();
    }

    /**
     * 이 테스트에서 만든 스키마를 지운다. 다른 테스트가 쓰는 public 스키마는 변경하지 않는다.
     */
    @AfterEach
    void 시험용_스키마를_지운다() {
        jdbcClient.sql("DROP SCHEMA IF EXISTS %s CASCADE".formatted(STAGED_SCHEMA)).update();
    }
}
