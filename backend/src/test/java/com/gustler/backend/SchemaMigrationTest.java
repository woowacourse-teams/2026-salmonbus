package com.gustler.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.support.IntegrationTest;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class SchemaMigrationTest {

    @Autowired
    private Flyway flyway;

    @Test
    void 앱_부팅_시_Flyway가_마이그레이션을_V1부터_순차적으로_모두_실행한다() {
        // when
        MigrationInfo[] actual = flyway.info().applied();

        // then
        assertThat(actual)
            .extracting(info -> info.getVersion().getVersion())
            .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
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

    private List<Date> installedOn() {
        return Arrays.stream(flyway.info().applied())
            .map(MigrationInfo::getInstalledOn)
            .toList();
    }
}
