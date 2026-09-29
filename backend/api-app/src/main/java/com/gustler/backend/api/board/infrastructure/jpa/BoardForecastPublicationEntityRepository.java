package com.gustler.backend.api.board.infrastructure.jpa;

import org.springframework.data.repository.Repository;

public interface BoardForecastPublicationEntityRepository
    extends Repository<ForecastPublicationJpaEntity, Long> {

    boolean existsByRouteVersionId(Long routeVersionId);
}
