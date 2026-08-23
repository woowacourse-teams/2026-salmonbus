package com.gustler.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.support.IntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class SchemaMigrationTest {

    @Autowired
    private Flyway flyway;

    @Test
    void 마이그레이션이_남김없이_적용된다() {
        assertThat(flyway.info().applied()).isNotEmpty();
        assertThat(flyway.info().pending()).isEmpty();
    }
}
