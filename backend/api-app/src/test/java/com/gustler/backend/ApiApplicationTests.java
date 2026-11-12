package com.gustler.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.gustler.backend.migration.MigrationNetworkTimeoutCallback;
import com.gustler.backend.migration.QualityRetirementMigrationCallback;
import com.gustler.backend.support.IntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class ApiApplicationTests {
    @Autowired
    Flyway flyway;

    @Test
    void API의_Flyway가_V66_조회_계획_콜백을_등록한다() {
        assertThat(flyway.getConfiguration().getCallbacks()).anyMatch(callback ->
            callback instanceof QualityRetirementMigrationCallback);
    }

    @Test
    void API의_Flyway가_마이그레이션_연결_소켓_제한_콜백을_등록한다() {
        assertThat(flyway.getConfiguration().getCallbacks()).anyMatch(callback ->
            callback instanceof MigrationNetworkTimeoutCallback);
    }

    @Test
    void GBIS_키_없이_컨텍스트가_뜬다() {
    }

    @Test
    void 수집기_클래스는_클래스패스에_아예_없다() {
        // when
        Throwable caughtFailure = catchThrowable(
            () -> Class.forName("com.gustler.backend.worker.scheduling.CollectionScheduler"));

        // then
        assertThat(caughtFailure)
            .isNotNull()
            .isInstanceOf(ClassNotFoundException.class);
    }
}
