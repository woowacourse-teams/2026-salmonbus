package com.gustler.backend.api.vehicle.infrastructure.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

/**
 * 노선 판본과 정류장 방문 순번으로 행을 구분하는 JPA 복합 식별자다.
 */
@Embeddable
public class RouteStopJpaId implements Serializable {
    @Column(name = "route_version_id", nullable = false)
    private Long routeVersionId;

    @Column(name = "stop_order", nullable = false)
    private Integer stopOrder;

    protected RouteStopJpaId() {
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RouteStopJpaId that)) {
            return false;
        }
        return Objects.equals(routeVersionId, that.routeVersionId)
            && Objects.equals(stopOrder, that.stopOrder);
    }

    @Override
    public int hashCode() {
        return Objects.hash(routeVersionId, stopOrder);
    }
}
