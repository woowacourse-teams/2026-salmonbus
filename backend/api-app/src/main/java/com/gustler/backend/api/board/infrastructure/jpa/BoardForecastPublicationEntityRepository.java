package com.gustler.backend.api.board.infrastructure.jpa;

import org.springframework.data.repository.Repository;

/**
 * 노선 판본의 예보 발행 존재 여부를 탑승판 조회에 제공하는 JPA 경계다.
 */
public interface BoardForecastPublicationEntityRepository
    extends Repository<ForecastPublicationJpaEntity, Long> {
    boolean existsByRouteVersionId(Long routeVersionId);
}
