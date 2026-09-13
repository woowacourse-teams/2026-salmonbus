package com.gustler.backend.processor.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.processor.ActiveModelDeployment;
import com.gustler.backend.processor.StagedModelDeployment;
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
    void 배포에서_계산_규칙_판과_신원을_같이_읽는다() {
        // given 신원을 같이 안 읽으면 올라온 계수가 이 행의 계수인지 대조할 수 없다
        final long activeDeploymentId = insertDeployment(ACTIVE, CALCULATION_VERSION_IN_USE);

        // when
        Optional<ActiveModelDeployment> actual = repository.findActive();

        // then
        assertThat(actual).contains(new ActiveModelDeployment(
            activeDeploymentId, CALCULATION_VERSION_IN_USE, RELEASE_ID, BUNDLE_DIGEST));
    }

    @Test
    void 적재_영수증은_STAGED_로_남는다() {
        // when
        final long actual = repository.stage(staged());

        // then
        assertThat(stateOf(actual)).isEqualTo(STAGED);
    }

    @Test
    void 적재만_해서는_도는_배포가_되지_않는다() {
        // when
        repository.stage(staged());

        // then
        assertThat(repository.findActive()).isEmpty();
    }

    @Test
    void STAGED_를_ACTIVE_로_올린다() {
        // given
        final long deploymentId = repository.stage(staged());

        // when
        repository.promoteToActive(deploymentId);

        // then
        assertThat(stateOf(deploymentId)).isEqualTo(ACTIVE);
    }

    @Test
    void 먼저_돌던_배포는_RETIRED_가_된다() {
        // given
        final long earlier = insertDeployment(ACTIVE, CALCULATION_VERSION_IN_USE);
        final long later = repository.stage(staged());

        // when
        repository.promoteToActive(later);

        // then
        assertThat(stateOf(earlier)).isEqualTo(RETIRED);
    }

    @Test
    void 올린_배포는_먼저_돌던_배포를_앞선_배포로_가리킨다() {
        // given
        final long earlier = insertDeployment(ACTIVE, CALCULATION_VERSION_IN_USE);
        final long later = repository.stage(staged());

        // when
        repository.promoteToActive(later);

        // then
        assertThat(predecessorOf(later)).isEqualTo(earlier);
    }

    @Test
    void 이미_ACTIVE_인_배포를_다시_올리면_아무_행도_안_바뀐다() {
        // given
        final long deploymentId = repository.stage(staged());
        repository.promoteToActive(deploymentId);

        // when
        final boolean actual = repository.promoteToActive(deploymentId);

        // then
        assertThat(actual).isFalse();
    }

    @Test
    void 이미_ACTIVE_인_배포를_다시_올려도_먼저_돌던_배포가_안_내려간다() {
        // given
        final long earlier = insertDeployment(ACTIVE, CALCULATION_VERSION_IN_USE);
        final long later = repository.stage(staged());
        repository.promoteToActive(later);

        // when 이미 올라간 것을 한 번 더 올린다
        repository.promoteToActive(later);

        // then 도는 배포는 여전히 하나다
        assertThat(activeCount()).isOne();
    }

    @Test
    void STAGED_가_아닌_식별자를_올리면_못_올렸다고_답한다() {
        // given
        final long retired = insertDeployment(RETIRED, CALCULATION_VERSION_RETIRED);

        // when
        final boolean actual = repository.promoteToActive(retired);

        // then
        assertThat(actual).isFalse();
    }

    private StagedModelDeployment staged() {
        return new StagedModelDeployment(
            UUID.randomUUID(), RELEASE_ID, MODEL_KEY, MODEL_VERSION, BUNDLE_DIGEST,
            PREDICTION_TARGET_VERSION, CALCULATION_VERSION_WAITING, SUPPORTED_SCOPE_DIGEST,
            DATA_UNTIL.toInstant());
    }

    private String stateOf(
        final long deploymentId
    ) {
        return jdbcClient.sql("SELECT state FROM model_deployment WHERE id = ?")
            .param(deploymentId)
            .query(String.class)
            .single();
    }

    private long predecessorOf(
        final long deploymentId
    ) {
        return jdbcClient.sql("SELECT predecessor_deployment_id FROM model_deployment WHERE id = ?")
            .param(deploymentId)
            .query(Long.class)
            .single();
    }

    private int activeCount() {
        return jdbcClient.sql("SELECT count(*) FROM model_deployment WHERE state = 'ACTIVE'")
            .query(Integer.class)
            .single();
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
