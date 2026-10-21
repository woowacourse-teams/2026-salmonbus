package com.gustler.backend.api.board.infrastructure.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import lombok.EqualsAndHashCode;

/**
 * 노선 판본과 정류장 방문 순번으로 행을 구분하는 JPA 복합 식별자다.
 */
@Embeddable
@EqualsAndHashCode
public class RouteStopJpaId implements Serializable {
    @Column(name = "route_version_id", nullable = false)
    private Long routeVersionId;

    @Column(name = "stop_order", nullable = false)
    private Integer stopOrder;

    protected RouteStopJpaId() {
    }

    public Long routeVersionId() {
        return routeVersionId;
    }

    public Integer stopOrder() {
        return stopOrder;
    }
}
