package com.gustler.backend.api.board.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import lombok.EqualsAndHashCode;

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
