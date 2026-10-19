package com.gustler.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.support.IntegrationTest;
import java.io.IOException;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.simple.JdbcClient;

@IntegrationTest
class SchemaMigrationTest {

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void 앱_부팅_시_Flyway가_마이그레이션을_V1부터_순차적으로_모두_실행한다() throws IOException {
        // given DB 이력이 아닌 실제 SQL 파일에서 예상 버전을 읽는다.
        List<MigrationVersion> expected = Arrays.stream(new PathMatchingResourcePatternResolver()
                .getResources("classpath:db/migration/V*__*.sql"))
            .map(Resource::getFilename)
            .map(name -> MigrationVersion.fromVersion(name.substring(1, name.indexOf("__"))))
            .sorted()
            .toList();
        assertThat(expected).isNotEmpty();

        // when
        MigrationInfo[] actual = flyway.info().applied();

        // then
        assertThat(actual)
            .extracting(MigrationInfo::getVersion)
            .containsExactlyElementsOf(expected);
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    void 앱을_다시_띄우면_기존에_돌았던_마이그레이션은_처음_실행됐던_시각을_유지하고_실행되지_않는다() {
        // given
        List<Date> firstRun = installedOn();

        // when
        flyway.migrate();

        // then
        List<Date> actual = installedOn();
        assertThat(actual).isEqualTo(firstRun);
    }

    @Test
    void 최신_성공_관측_인덱스는_유지하고_미사용_배치_시도_인덱스는_제거한다() {
        // when
        boolean actual = jdbcClient.sql("""
                SELECT to_regclass('ix_batch_forecast_candidates') IS NOT NULL
                   AND to_regclass('ix_batch_latest_attempt') IS NULL
                """)
            .query(Boolean.class)
            .single();

        // then
        assertThat(actual).isTrue();
    }

    private List<Date> installedOn() {
        return Arrays.stream(flyway.info().applied())
            .map(MigrationInfo::getInstalledOn)
            .toList();
    }
}
