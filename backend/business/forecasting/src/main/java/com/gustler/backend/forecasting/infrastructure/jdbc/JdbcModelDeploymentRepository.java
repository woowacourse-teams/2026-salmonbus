package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.domain.deployment.ActiveModelDeployment;
import com.gustler.backend.forecasting.domain.deployment.ModelDeploymentRepository;
import com.gustler.backend.forecasting.domain.deployment.StagedModelDeployment;
import java.sql.Timestamp;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 대기 모델 배포를 기록하고 활성 모델 전환을 저장 경계에서 확정한다.
 */
@Repository
public class JdbcModelDeploymentRepository implements ModelDeploymentRepository {
    /**
     * 활성 배포 하나를 조회한다. 상태를 이름 붙인 파라미터로 바인딩하지 않고 'ACTIVE' 를 그대로 쓴다.
     * ux_model_deployment_single_active 의 부분 조건과 문구가 같아야 쿼리 플래너가 해당 인덱스를 선택한다.
     *
     * <p>행이 둘이면 예외가 발생한다. 활성 배포가 둘인 상태는 이 인덱스가 막고 있으므로
     * 임의로 하나를 골라 예보를 발행하지 않고 중단한다.
     */
    private static final String SELECT_ACTIVE_DEPLOYMENT = """
        SELECT id, calculation_version, release_id, bundle_digest
        FROM model_deployment
        WHERE state = 'ACTIVE'
        """;

    private static final String INSERT_STAGED_DEPLOYMENT = """
        INSERT INTO model_deployment (
            deployment_key, release_id, model_key, model_version, bundle_digest,
            prediction_target_version, calculation_version, supported_scope_digest,
            data_until, state)
        VALUES (
            :deploymentKey, :releaseId, :modelKey, :modelVersion, :bundleDigest,
            :predictionTargetVersion, :calculationVersion, :supportedScopeDigest,
            :dataUntil, 'STAGED')
        RETURNING id
        """;

    /**
     * 승격을 직렬화한다. transaction이 끝나면 잠금이 자동으로 풀린다.
     *
     * <p>기존 배포 해제와 새 배포 활성화가 두 문장이므로 그 사이에 다른 적재가 끼면 ACTIVE가 잠깐 둘이 되고
     * ux_model_deployment_single_active 가 이를 예외로 막는다. <b>그 예외를 잡아 실패로
     * 바꾸는 것만으로는 부족하다.</b> PostgreSQL은 제약을 위반한 transaction 전체를 사용할 수 없게 만들어
     * 뒤이은 commit도 실패한다. 기동 적재에서도 이 문제가 생겨 앱이 시작되지 않는다.
     *
     * <p>따라서 예외가 발생하기 전에 대기시킨다. 읽기는 차단하지 않는다. 예보가 활성 배포를 읽는 조회는
     * 일반 SELECT이므로 이 잠금과 충돌하지 않는다.
     *
     * <p>이 문장은 transaction 안에서만 실행할 수 있다. 밖에서 호출하면 PostgreSQL이 거절한다.
     * 기존 배포 해제와 새 배포 활성화는 같은 transaction이어야 한다는 저장소의 조건을 유지한다.
     */
    private static final String LOCK_DEPLOYMENTS = """
        LOCK TABLE model_deployment IN EXCLUSIVE MODE
        """;

    /**
     * 기존 활성 배포를 해제한다. 승격할 행이 아직 STAGED일 때만 해제한다.
     *
     * <p>활성 배포를 하나로 제한하는 부분 인덱스 때문에 해제와 활성화를 한 문장에 담을 수 없다.
     * 두 행을 한 UPDATE로 바꾸면 중간 상태에서 ACTIVE가 둘이 되는 순간이 생겨 인덱스가 막는다.
     * 따라서 해제와 활성화를 같은 transaction의 두 문장으로 실행한다.
     */
    private static final String RETIRE_ACTIVE_DEPLOYMENT = """
        UPDATE model_deployment
        SET state = 'RETIRED', retired_at = now()
        WHERE state = 'ACTIVE'
          AND EXISTS (
              SELECT 1 FROM model_deployment staged
              WHERE staged.id = :deploymentId AND staged.state = 'STAGED')
        """;

    /**
     * STAGED 하나를 승격한다. 상태 조건이 있어 두 번 호출해도 한 번만 반영한다.
     *
     * <p>이전 배포를 predecessor로 기록해 예보 행 밖에서도 배포 전환 이력을 확인할 수 있게 한다.
     */
    private static final String ACTIVATE_STAGED_DEPLOYMENT = """
        UPDATE model_deployment
        SET state = 'ACTIVE',
            activated_at = now(),
            predecessor_deployment_id = (
                SELECT id FROM model_deployment
                WHERE state = 'RETIRED'
                ORDER BY retired_at DESC NULLS LAST, id DESC
                LIMIT 1)
        WHERE id = :deploymentId AND state = 'STAGED'
        """;

    private final JdbcClient jdbcClient;

    public JdbcModelDeploymentRepository(
        JdbcClient jdbcClient
    ) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<ActiveModelDeployment> findActive() {
        return jdbcClient.sql(SELECT_ACTIVE_DEPLOYMENT)
            .query((resultSet, rowNumber) -> new ActiveModelDeployment(
                resultSet.getLong("id"),
                resultSet.getString("calculation_version"),
                resultSet.getString("release_id"),
                resultSet.getString("bundle_digest")))
            .optional();
    }

    @Override
    public long stage(
        StagedModelDeployment staged
    ) {
        return jdbcClient.sql(INSERT_STAGED_DEPLOYMENT)
            .param("deploymentKey", staged.deploymentKey())
            .param("releaseId", staged.releaseId())
            .param("modelKey", staged.modelKey())
            .param("modelVersion", staged.modelVersion())
            .param("bundleDigest", staged.bundleDigest())
            .param("predictionTargetVersion", staged.predictionTargetVersion())
            .param("calculationVersion", staged.calculationVersion())
            .param("supportedScopeDigest", staged.supportedScopeDigest())
            .param("dataUntil", Timestamp.from(staged.dataUntil()))
            .query(Long.class)
            .single();
    }

    @Override
    public boolean promoteToActive(
        final long deploymentId
    ) {
        jdbcClient.sql(LOCK_DEPLOYMENTS).update();
        jdbcClient.sql(RETIRE_ACTIVE_DEPLOYMENT).param("deploymentId", deploymentId).update();
        return jdbcClient.sql(ACTIVATE_STAGED_DEPLOYMENT).param("deploymentId", deploymentId).update() == 1;
    }
}
