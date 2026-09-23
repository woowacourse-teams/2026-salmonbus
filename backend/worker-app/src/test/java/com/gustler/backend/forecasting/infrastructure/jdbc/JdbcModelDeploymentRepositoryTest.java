package com.gustler.backend.forecasting.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.forecasting.domain.model.ActiveModelDeployment;
import com.gustler.backend.forecasting.domain.model.ActiveModelSlot;
import com.gustler.backend.forecasting.domain.model.ModelActivation;
import com.gustler.backend.forecasting.domain.model.ModelIdentity;
import com.gustler.backend.forecasting.domain.model.StagedModelDeployment;
import com.gustler.backend.support.IntegrationTest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class JdbcModelDeploymentRepositoryTest {

    private static final Instant DATA_UNTIL = Instant.parse("2026-08-18T15:00:00Z");

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private JdbcModelDeploymentRepository repository;

    @Test
    void 활성_배포의_모든_식별_정보를_함께_조회한다() {
        // given
        insertDeployment("RETIRED", identity("retired", "calculation-v1.1.0"));
        ModelIdentity identity = identity("active", "calculation-v1.2.0");
        final long activeId = insertDeployment("ACTIVE", identity);
        insertDeployment("STAGED", identity("waiting", "calculation-v1.3.0"));

        // when
        Optional<ActiveModelDeployment> actual = repository.findActive();

        // then
        assertThat(actual).contains(new ActiveModelDeployment(activeId, identity));
    }

    @Test
    void 활성_배포가_없으면_비어_있다() {
        // given
        insertDeployment("STAGED", identity("waiting", "calculation-v1.3.0"));
        insertDeployment("RETIRED", identity("retired", "calculation-v1.1.0"));

        // when
        Optional<ActiveModelDeployment> actual = repository.findActive();

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 준비한_배포는_활성화하기_전까지_STAGED로_남는다() {
        // given
        ModelIdentity identity = identity("waiting", "calculation-v1.3.0");

        // when
        final long deploymentId = repository.stage(staged(identity));

        // then
        assertThat(stateOf(deploymentId)).isEqualTo("STAGED");
        assertThat(repository.findActive()).isEmpty();
        assertThat(repository.findActiveSlot()).isEqualTo(new ActiveModelSlot(0, Optional.empty()));
    }

    @Test
    void 처음_활성화하면_슬롯의_배포와_버전이_함께_바뀐다() {
        // given
        ModelIdentity identity = identity("first", "calculation-v1.2.0");
        final long deploymentId = repository.stage(staged(identity));
        ActiveModelSlot previous = repository.lockActiveSlot();

        // when
        repository.activate(deploymentId, previous, previous.version() + 1);

        // then
        assertThat(stateOf(deploymentId)).isEqualTo("ACTIVE");
        assertThat(repository.findActiveSlot()).isEqualTo(new ActiveModelSlot(1,
            Optional.of(new ActiveModelDeployment(deploymentId, identity))));
        assertThat(jdbcClient.sql("SELECT predecessor_deployment_id IS NULL FROM model_deployment WHERE id = ?")
            .param(deploymentId).query(Boolean.class).single()).isTrue();
    }

    @Test
    void 새_배포를_활성화하면_이전_배포를_종료하고_연결을_남긴다() {
        // given
        final long previousId = activate(identity("first", "calculation-v1.2.0"));
        ModelIdentity nextIdentity = identity("next", "calculation-v1.3.0");
        final long nextId = repository.stage(staged(nextIdentity));
        ActiveModelSlot previous = repository.lockActiveSlot();

        // when
        repository.activate(nextId, previous, previous.version() + 1);

        // then
        assertThat(stateOf(previousId)).isEqualTo("RETIRED");
        assertThat(stateOf(nextId)).isEqualTo("ACTIVE");
        assertThat(jdbcClient.sql("SELECT predecessor_deployment_id FROM model_deployment WHERE id = ?")
            .param(nextId).query(Long.class).single()).isEqualTo(previousId);
        assertThat(repository.findActiveSlot()).isEqualTo(new ActiveModelSlot(2,
            Optional.of(new ActiveModelDeployment(nextId, nextIdentity))));
        assertThat(jdbcClient.sql("SELECT count(*) FROM model_deployment WHERE state = 'ACTIVE'")
            .query(Integer.class).single()).isOne();
    }

    @Test
    void 준비_상태가_아닌_배포는_활성화하지_못한다() {
        // given
        final long retiredId = insertDeployment("RETIRED", identity("retired", "calculation-v1.1.0"));
        ActiveModelSlot previous = repository.lockActiveSlot();

        // when, then
        assertThatThrownBy(() -> repository.activate(retiredId, previous, 1))
            .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
            .hasRootCauseInstanceOf(IllegalStateException.class)
            .hasRootCauseMessage("활성 모델 변경 중 저장 상태가 달라졌습니다");
        assertThat(repository.findActive()).isEmpty();
        assertThat(repository.findActiveSlot()).isEqualTo(previous);
    }

    @Test
    void 요청_기록에서_입력과_확정된_결과를_모두_복원한다() {
        // given
        ModelIdentity identity = identity("recorded", "calculation-v1.2.0");
        final long deploymentId = activate(identity);
        ModelActivation activation = new ModelActivation(UUID.randomUUID(), 0,
            new ActiveModelDeployment(deploymentId, identity), 1,
            Instant.parse("2026-09-23T01:02:03.123456Z"));

        // when
        repository.recordActivation(activation);

        // then
        assertThat(repository.findActivation(activation.requestId())).contains(activation);
        assertThat(repository.findActivation(UUID.randomUUID())).isEmpty();
    }

    private long activate(ModelIdentity identity) {
        ActiveModelSlot previous = repository.lockActiveSlot();
        final long id = repository.stage(staged(identity));
        repository.activate(id, previous, previous.version() + 1);
        return id;
    }

    private ModelIdentity identity(String releaseId, String calculationVersion) {
        return new ModelIdentity(releaseId, "seat-forecast", "model-v1.0.0", "0".repeat(64),
            "remaining-seats-v1", calculationVersion, "1".repeat(64), DATA_UNTIL);
    }

    private StagedModelDeployment staged(ModelIdentity identity) {
        return new StagedModelDeployment(UUID.randomUUID(), identity.releaseId(), identity.modelKey(),
            identity.modelVersion(), identity.bundleDigest(), identity.predictionTargetVersion(),
            identity.calculationVersion(), identity.supportedScopeDigest(), identity.dataUntil());
    }

    private String stateOf(long id) {
        return jdbcClient.sql("SELECT state FROM model_deployment WHERE id = ?")
            .param(id).query(String.class).single();
    }

    private long insertDeployment(String state, ModelIdentity identity) {
        return jdbcClient.sql("""
                INSERT INTO model_deployment (
                    deployment_key, release_id, model_key, model_version, bundle_digest,
                    prediction_target_version, calculation_version, supported_scope_digest,
                    data_until, state
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """)
            .params(UUID.randomUUID(), identity.releaseId(), identity.modelKey(), identity.modelVersion(),
                identity.bundleDigest(), identity.predictionTargetVersion(), identity.calculationVersion(),
                identity.supportedScopeDigest(), java.sql.Timestamp.from(identity.dataUntil()), state)
            .query(Long.class).single();
    }
}
