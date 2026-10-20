package com.gustler.backend.routecatalog.infrastructure.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;

/**
 * 노선 판본과 방문 순번으로 정류장 행을 식별하는 복합키다.
 */
@Embeddable
public record RouteStopJpaId(
    @Column(name = "route_version_id")
    Long routeVersionId,

    @Column(name = "stop_order")
    Integer stopOrder
) implements Serializable {
}
