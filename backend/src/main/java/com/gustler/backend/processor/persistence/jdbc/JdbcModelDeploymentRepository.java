package com.gustler.backend.processor.persistence.jdbc;

import com.gustler.backend.processor.ActiveModelDeployment;
import com.gustler.backend.processor.ModelDeploymentRepository;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 지금 도는 계수 배포를 SQL 로 직접 읽는다. api 의 JPA 엔티티를 쓰지 않는다.
 *
 * <p>model_deployment 를 매핑한 엔티티가 다른 패키지에 이미 있다. 여기서 엔티티를 하나 더
 * 만들면 Hibernate 가 같은 테이블을 두 번 매핑했다며 뜨지 않는다.
 *
 * <p>배포에서 id 와 calculation_version 만 읽는다. 예보 행이 가리켜야 하는 것이 그 둘이다.
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
        SELECT id, calculation_version
        FROM model_deployment
        WHERE state = 'ACTIVE'
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
                resultSet.getString("calculation_version")))
            .optional();
    }
}
