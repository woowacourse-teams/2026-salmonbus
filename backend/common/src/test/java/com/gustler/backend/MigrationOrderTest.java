package com.gustler.backend;

import static org.assertj.core.api.Assertions.assertThat;

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
     * <p>이 브랜치는 V45 즐겨찾기 이후 기점 관측의 품질 방향 판정을 고친다.
     */
    private static final List<String> MIGRATIONS_THIS_BRANCH_ADDS =
        List.of("first_stop_quality_direction");

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
        return MIGRATIONS_THIS_BRANCH_ADDS.stream().anyMatch(fileName::contains);
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
