package com.gustler.backend.processor.persistence.jdbc;

import com.gustler.backend.processor.ActiveModelDeployment;
import com.gustler.backend.processor.ModelDeploymentRepository;
import com.gustler.backend.processor.StagedModelDeployment;
import java.sql.Timestamp;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 계수 배포를 SQL 로 직접 읽고 쓴다. api 의 JPA 엔티티를 쓰지 않는다.
 *
 * <p>model_deployment 를 매핑한 엔티티가 다른 패키지에 이미 있다. 여기서 엔티티를 하나 더
 * 만들면 Hibernate 가 같은 테이블을 두 번 매핑했다며 뜨지 않는다.
 */
@Repository
public class JdbcModelDeploymentRepository implements ModelDeploymentRepository {

    /**
     * 도는 배포 하나. 상태를 이름 붙인 파라미터로 안 묶고 'ACTIVE' 를 글자 그대로 적는다.
     * ux_model_deployment_single_active 의 부분 조건과 글자가 같아야 계획기가 그 인덱스를 고른다.
     *
     * <p>행이 둘이면 여기서 예외가 난다. 도는 배포가 둘인 것은 그 인덱스가 막고 있는 상태라,
     * 조용히 하나를 골라 예보를 내는 것보다 멈추는 편이 낫다.
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
     * 승격을 한 줄로 세운다. transaction 이 끝나면 저절로 풀린다.
     *
     * <p>내리기와 올리기가 두 문장이라 그 사이에 다른 적재가 끼면 ACTIVE 가 잠깐 둘이 되고,
     * ux_model_deployment_single_active 가 그 순간을 예외로 막는다. <b>그 예외를 잡아 실패로
     * 바꾸는 것으로는 안 끝난다.</b> PostgreSQL 은 제약을 어긴 transaction 을 통째로 못 쓰게 만들어서
     * 뒤이은 commit 이 또 실패한다. 기동 적재가 그 자리라 앱이 안 뜬다.
     *
     * <p>그래서 예외가 나기 전에 기다리게 한다. 읽기는 안 막는다. 예보가 도는 배포를 읽는 것은
     * 그냥 SELECT 라 이 잠금과 안 부딪친다.
     *
     * <p>이 문장은 transaction 안에서만 된다. 밖에서 부르면 PostgreSQL 이 거절한다. 내리기와
     * 올리기가 한 transaction 이어야 한다는 것이 어차피 이 저장소의 조건이라 그대로 둔다.
     */
    private static final String LOCK_DEPLOYMENTS = """
        LOCK TABLE model_deployment IN EXCLUSIVE MODE
        """;

    /**
     * 먼저 돌던 배포를 내린다. 올릴 행이 아직 STAGED 일 때만 내린다.
     *
     * <p>내리기와 올리기를 한 문장에 못 담는 이유는 도는 배포가 하나뿐임을 강제하는 부분 인덱스다.
     * 두 행을 한 UPDATE 로 바꾸면 중간 상태에서 ACTIVE 가 둘이 되는 순간이 생겨 인덱스가 막는다.
     * 그래서 내리고 올리는 두 문장을 같은 transaction 에 둔다.
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
     * STAGED 하나를 올린다. 조건에 상태가 들어 있어서 두 번 불러도 한 번만 먹는다.
     *
     * <p>앞선 배포를 predecessor 로 적는다. 그 열이 있어야 어느 배포에서 어느 배포로 넘어갔는지가
     * 예보 행 밖에서도 읽힌다.
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
