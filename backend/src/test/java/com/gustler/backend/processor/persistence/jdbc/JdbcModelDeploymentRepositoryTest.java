package com.gustler.backend.processor.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.processor.ActiveModelDeployment;
import com.gustler.backend.support.IntegrationTest;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class JdbcModelDeploymentRepositoryTest {

    private static final String STAGED = "STAGED";
    private static final String ACTIVE = "ACTIVE";
    private static final String RETIRED = "RETIRED";

    private static final String CALCULATION_VERSION_IN_USE = "calculation-v1.2.0";
    private static final String CALCULATION_VERSION_WAITING = "calculation-v1.3.0";
    private static final String CALCULATION_VERSION_RETIRED = "calculation-v1.1.0";

    private static final String RELEASE_ID = "release-2026-08-19";
    private static final String MODEL_KEY = "seat-forecast";
    private static final String MODEL_VERSION = "model-v1.0.0";
    private static final String BUNDLE_DIGEST = "0".repeat(64);
    private static final String PREDICTION_TARGET_VERSION = "remaining-seats-v1";
    private static final String SUPPORTED_SCOPE_DIGEST = "1".repeat(64);
    private static final OffsetDateTime DATA_UNTIL = OffsetDateTime.parse("2026-08-19T00:00+09:00");

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private JdbcModelDeploymentRepository repository;

    @Test
    void 도는_배포가_하나면_그것을_읽는다() {
        // given
        insertDeployment(RETIRED, CALCULATION_VERSION_RETIRED);
        final long activeDeploymentId = insertDeployment(ACTIVE, CALCULATION_VERSION_IN_USE);
        insertDeployment(STAGED, CALCULATION_VERSION_WAITING);

        // when
        Optional<ActiveModelDeployment> actual = repository.findActive();

        // then
        assertThat(actual).map(ActiveModelDeployment::id).contains(activeDeploymentId);
    }

    @Test
    void 도는_배포가_없으면_비어_있다() {
        // given
        insertDeployment(STAGED, CALCULATION_VERSION_WAITING);
        insertDeployment(RETIRED, CALCULATION_VERSION_RETIRED);

        // when
        Optional<ActiveModelDeployment> actual = repository.findActive();

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 배포에서_계산_규칙_판을_같이_읽는다() {
        // given
        final long activeDeploymentId = insertDeployment(ACTIVE, CALCULATION_VERSION_IN_USE);

        // when
        Optional<ActiveModelDeployment> actual = repository.findActive();

        // then
        assertThat(actual)
            .contains(new ActiveModelDeployment(activeDeploymentId, CALCULATION_VERSION_IN_USE));
    }

    /** 배포 키는 행마다 달라야 한다. ux_model_deployment_key 가 같은 값을 두 번 못 넣게 막는다. */
    private long insertDeployment(
        String state,
        String calculationVersion
    ) {
        return jdbcClient.sql("""
                INSERT INTO model_deployment (
                    deployment_key, release_id, model_key, model_version, bundle_digest,
                    prediction_target_version, calculation_version, supported_scope_digest,
                    data_until, state
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """)
            .params(
                UUID.randomUUID(), RELEASE_ID, MODEL_KEY, MODEL_VERSION, BUNDLE_DIGEST,
                PREDICTION_TARGET_VERSION, calculationVersion, SUPPORTED_SCOPE_DIGEST,
                DATA_UNTIL, state
            )
            .query(Long.class)
            .single();
    }
}
