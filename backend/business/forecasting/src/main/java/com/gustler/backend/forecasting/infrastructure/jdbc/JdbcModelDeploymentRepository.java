package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.domain.model.ActiveModelDeployment;
import com.gustler.backend.forecasting.domain.model.ActiveModelSlot;
import com.gustler.backend.forecasting.domain.model.ModelActivation;
import com.gustler.backend.forecasting.domain.model.ModelDeploymentRepository;
import com.gustler.backend.forecasting.domain.model.ModelIdentity;
import com.gustler.backend.forecasting.domain.model.StagedModelDeployment;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
public class JdbcModelDeploymentRepository implements ModelDeploymentRepository {
    private static final String IDENTITY_COLUMNS = """
        d.id, d.release_id, d.model_key, d.model_version, d.bundle_digest,
        d.prediction_target_version, d.calculation_version, d.supported_scope_digest, d.data_until
        """;
    private final JdbcClient jdbcClient;

    public JdbcModelDeploymentRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<ActiveModelDeployment> findActive() {
        return jdbcClient.sql("SELECT " + IDENTITY_COLUMNS + " FROM model_deployment d WHERE d.state = 'ACTIVE'")
            .query((rs, row) -> deployment(rs)).optional();
    }

    @Override
    public ActiveModelSlot findActiveSlot() {
        return selectSlot();
    }

    @Override
    public ActiveModelSlot lockActiveSlot() {
        requireTransaction();
        SlotReference slot = jdbcClient.sql("""
                SELECT version, model_deployment_id FROM model_active_slot WHERE id = 1 FOR UPDATE
                """).query((rs, row) -> new SlotReference(rs.getLong("version"),
                rs.getObject("model_deployment_id", Long.class))).single();
        // 잠금 대기 중 다른 트랜잭션이 새 배포를 추가할 수 있어 잠금을 얻은 뒤 별도 조회한다.
        Optional<ActiveModelDeployment> deployment = slot.deploymentId() == null ? Optional.empty()
            : Optional.of(jdbcClient.sql("SELECT " + IDENTITY_COLUMNS + " FROM model_deployment d WHERE d.id = :id")
                .param("id", slot.deploymentId()).query((rs, row) -> deployment(rs)).single());
        return new ActiveModelSlot(slot.version(), deployment);
    }

    private ActiveModelSlot selectSlot() {
        return jdbcClient.sql("SELECT s.version, " + IDENTITY_COLUMNS + """
                FROM model_active_slot s
                LEFT JOIN model_deployment d ON d.id = s.model_deployment_id
                WHERE s.id = 1
                """)
            .query((rs, row) -> new ActiveModelSlot(rs.getLong("version"),
                rs.getObject("id") == null ? Optional.empty() : Optional.of(deployment(rs))))
            .single();
    }

    private record SlotReference(long version, Long deploymentId) { }

    @Override
    public Optional<ModelActivation> findActivation(UUID requestId) {
        return jdbcClient.sql("""
                SELECT a.request_id, a.expected_active_version, a.resulting_active_version,
                       a.activated_at AS request_activated_at,
                """ + IDENTITY_COLUMNS + """
                FROM model_activation_request a
                JOIN model_deployment d ON d.id = a.target_deployment_id
                WHERE a.request_id = :requestId
                """)
            .param("requestId", requestId)
            .query((rs, row) -> new ModelActivation(rs.getObject("request_id", UUID.class),
                rs.getLong("expected_active_version"), deployment(rs), rs.getLong("resulting_active_version"),
                rs.getTimestamp("request_activated_at").toInstant()))
            .optional();
    }

    @Override
    public long stage(StagedModelDeployment staged) {
        requireTransaction();
        return jdbcClient.sql("""
                INSERT INTO model_deployment (
                    deployment_key, release_id, model_key, model_version, bundle_digest,
                    prediction_target_version, calculation_version, supported_scope_digest,
                    data_until, state)
                VALUES (:deploymentKey, :releaseId, :modelKey, :modelVersion, :bundleDigest,
                    :predictionTargetVersion, :calculationVersion, :supportedScopeDigest, :dataUntil, 'STAGED')
                RETURNING id
                """)
            .param("deploymentKey", staged.deploymentKey())
            .param("releaseId", staged.releaseId())
            .param("modelKey", staged.modelKey())
            .param("modelVersion", staged.modelVersion())
            .param("bundleDigest", staged.bundleDigest())
            .param("predictionTargetVersion", staged.predictionTargetVersion())
            .param("calculationVersion", staged.calculationVersion())
            .param("supportedScopeDigest", staged.supportedScopeDigest())
            .param("dataUntil", Timestamp.from(staged.dataUntil()))
            .query(Long.class).single();
    }

    @Override
    public void activate(long deploymentId, ActiveModelSlot previous, long resultingVersion) {
        requireTransaction();
        Long previousId = previous.deployment().map(ActiveModelDeployment::id).orElse(null);
        if (previousId != null) {
            requireUpdated(jdbcClient.sql("""
                    UPDATE model_deployment SET state = 'RETIRED', retired_at = now()
                    WHERE id = :id AND state = 'ACTIVE'
                    """).param("id", previousId).update());
        }
        requireUpdated(jdbcClient.sql("""
                UPDATE model_deployment SET state = 'ACTIVE', activated_at = now(),
                    predecessor_deployment_id = :predecessor
                WHERE id = :id AND state = 'STAGED'
                """).param("predecessor", previousId, java.sql.Types.BIGINT)
            .param("id", deploymentId).update());
        requireUpdated(jdbcClient.sql("""
                UPDATE model_active_slot SET model_deployment_id = :target, version = :nextVersion
                WHERE id = 1 AND version = :expectedVersion
                  AND model_deployment_id IS NOT DISTINCT FROM :previous
                """).param("target", deploymentId).param("nextVersion", resultingVersion)
            .param("expectedVersion", previous.version()).param("previous", previousId, java.sql.Types.BIGINT)
            .update());
    }

    @Override
    public void recordActivation(ModelActivation activation) {
        requireTransaction();
        jdbcClient.sql("""
                INSERT INTO model_activation_request(request_id, expected_active_version,
                    target_deployment_id, resulting_active_version, activated_at)
                VALUES (:request, :expected, :target, :result, :at)
                """).param("request", activation.requestId()).param("expected", activation.expectedActiveVersion())
            .param("target", activation.target().id()).param("result", activation.resultingActiveVersion())
            .param("at", Timestamp.from(activation.activatedAt())).update();
    }

    private static ActiveModelDeployment deployment(ResultSet rs) throws SQLException {
        return new ActiveModelDeployment(rs.getLong("id"), new ModelIdentity(
            rs.getString("release_id"), rs.getString("model_key"), rs.getString("model_version"),
            rs.getString("bundle_digest"), rs.getString("prediction_target_version"),
            rs.getString("calculation_version"), rs.getString("supported_scope_digest"),
            rs.getTimestamp("data_until").toInstant()));
    }

    private static void requireUpdated(int count) {
        if (count != 1) {
            throw new IllegalStateException("활성 모델 변경 중 저장 상태가 달라졌습니다");
        }
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("모델 배포 변경에는 트랜잭션이 필요합니다");
        }
    }
}
