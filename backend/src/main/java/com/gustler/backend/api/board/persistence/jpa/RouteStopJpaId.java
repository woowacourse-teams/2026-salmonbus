package com.gustler.backend.api.board.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
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

    @Override
    public boolean equals(final Object other) {
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
