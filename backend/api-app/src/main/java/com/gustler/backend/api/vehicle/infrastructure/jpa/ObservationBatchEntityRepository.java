package com.gustler.backend.api.vehicle.infrastructure.jpa;

import com.gustler.backend.api.route.infrastructure.jpa.RouteVersionJpaEntity;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 노선 판본의 정상 수신 관측 묶음을 최신 수신 시각과 식별자 순으로 찾는 JPA 조회 경계다.
 */
public interface ObservationBatchEntityRepository
    extends Repository<ObservationBatchJpaEntity, Long> {
    @Query("""
        SELECT batch
        FROM ObservationBatchJpaEntity batch
        WHERE batch.routeVersion = :routeVersion
          AND batch.outcome IN ('SUCCESS_ROWS', 'SUCCESS_EMPTY')
          AND batch.responseReceivedAt IS NOT NULL
        ORDER BY batch.responseReceivedAt DESC,
                 batch.id DESC
        """)
    List<ObservationBatchJpaEntity> findLatestSuccessfulByRouteVersion(
        @Param("routeVersion") RouteVersionJpaEntity routeVersion,
        Pageable pageable
    );
}
