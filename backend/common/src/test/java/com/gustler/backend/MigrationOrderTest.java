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
 * 마이그레이션 번호가 브랜치 머지 순서와 같은지 본다.
 *
 * <p>SchemaMigrationTest 는 매번 빈 DB 가 파일 전부를 한 번에 보고 번호 순으로 돌린다.
 * 그래서 번호가 머지 순서와 거꾸로 배정돼도 못 잡는다. 실제로 그런 적이 있다.
 * 이 티켓의 마이그레이션이 궤적 마이그레이션보다 낮은 번호였고, 궤적이 먼저 머지된 DB 에서는
 * Flyway 기본값(outOfOrder=false)이 낮은 번호를 안 돌리고 검증에서 막았다.
 *
 * <p>여기서는 이 티켓이 더하는 파일만 빼고 먼저 올린 DB 를 만든 뒤, 전부 보이는 상태로 다시 올린다.
 * 앞 브랜치가 머지된 dev 위에 이 브랜치가 올라오는 것과 같은 순서다.
 */
@IntegrationTest
class MigrationOrderTest {

    /**
     * 이 브랜치가 더하는 마이그레이션. 브랜치마다 다르므로 여기만 고치면 된다.
     *
     * <p>번호로 고르면 안 된다. "제일 높은 번호가 이 브랜치 것" 을 가정으로 깔면
     * 번호가 거꾸로 배정돼도 그 가정에 맞춰 다른 파일을 빼게 되고, 결국 늘 순서가 맞아서 통과한다.
     * 이름으로 고르고, 그 파일들이 정말 마지막 번호인지는 아래에서 따로 단언한다.
     *
     * <p>이 브랜치는 당일 성적 조회를 받는 부분 인덱스 하나만 더한다. 계수는 번들 안에 있어 열이 되지 않고,
     * 어떤 계수 묶음이 도는지를 담는 model_deployment 는 V2 에 이미 있다.
     */
    private static final List<String> MIGRATIONS_THIS_BRANCH_ADDS =
        List.of("forecast_settled_arrival_index");

    private static final String MIGRATION_LOCATION = "db/migration";
    private static final String STAGED_SCHEMA = "staged_deploy";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void 앞_브랜치가_머지된_DB_에_이_브랜치의_마이그레이션이_올라간다() {
        // given 이 브랜치 것만 빼고 먼저 올린다
        migrateWith("filesystem:" + stagedMigrations());

        // when 전부 보이는 상태로 다시 올린다
        final MigrateResult actual = migrateWith("classpath:" + MIGRATION_LOCATION);

        // then
        assertThat(actual.success).isTrue();
    }

    @Test
    void 앞_브랜치가_머지된_DB_에서도_모든_마이그레이션이_적용된_상태가_된다() {
        // given
        migrateWith("filesystem:" + stagedMigrations());
        migrateWith("classpath:" + MIGRATION_LOCATION);

        // when
        final List<Integer> actual = versionsOf(flywayAt("classpath:" + MIGRATION_LOCATION).info().applied());

        // then 하나도 안 빠지고 다 적용된 상태다
        assertThat(actual).hasSize(migrationCount());
    }

    @Test
    void 이_브랜치가_더하는_마이그레이션이_마지막_번호를_차지한다() {
        // when
        final List<Integer> actual = thisBranchMigrations().stream().map(this::versionOf).toList();

        // then 앞 브랜치가 먼저 머지되므로 이 브랜치 것이 마지막 번호들이어야 한다
        final int firstOfThisBranch = migrationCount() - MIGRATIONS_THIS_BRANCH_ADDS.size() + 1;
        assertThat(actual).containsExactlyElementsOf(
            IntStream.rangeClosed(firstOfThisBranch, migrationCount()).boxed().toList());
    }

    @Test
    void 마이그레이션_번호에_빈_자리도_겹치는_자리도_없다() {
        // when
        final List<Integer> actual = versionsOf(flywayAt("classpath:" + MIGRATION_LOCATION).info().all());

        // then 1 부터 하나씩 오른다. 번호를 나눠 가지다 빠뜨리거나 겹치면 여기서 걸린다
        assertThat(actual).containsExactly(
            IntStream.rangeClosed(1, actual.size()).boxed().toArray(Integer[]::new));
    }

    /** 스키마를 만든 기록은 버전이 없다. 마이그레이션만 센다. */
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

    /** 이 브랜치가 더하는 마이그레이션. 적힌 수와 안 맞으면 상수가 낡은 것이다. */
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

    /** 이 브랜치가 더하는 파일만 뺀 마이그레이션 묶음. 앞 브랜치까지만 머지된 dev 와 같은 상태다. */
    private Path stagedMigrations() {
        try {
            final Path source = Path.of("src/main/resources", MIGRATION_LOCATION);
            final Path staged = Files.createTempDirectory("staged-migration");
            try (Stream<Path> files = Files.list(source)) {
                files.filter(file -> !isThisBranchMigration(file.getFileName().toString()))
                    .forEach(file -> copyInto(staged, file));
            }
            return staged;
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void copyInto(
        Path staged,
        Path file
    ) {
        try {
            Files.copy(file, staged.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 앱이 쓰는 스키마를 안 건드리려고 따로 판 스키마에 올린다. */
    private MigrateResult migrateWith(
        final String location
    ) {
        return flywayAt(location).migrate();
    }

    private Flyway flywayAt(
        final String location
    ) {
        return Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .schemas(STAGED_SCHEMA)
            .createSchemas(true)
            .load();
    }

    /** 이 테스트가 판 스키마를 지운다. 다른 테스트가 보는 public 스키마는 안 건드린다. */
    @AfterEach
    void 시험용_스키마를_지운다() {
        jdbcClient.sql("DROP SCHEMA IF EXISTS %s CASCADE".formatted(STAGED_SCHEMA)).update();
    }
}
