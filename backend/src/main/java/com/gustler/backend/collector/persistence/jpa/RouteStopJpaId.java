package com.gustler.backend.collector.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;

@Embeddable
public record RouteStopJpaId(
    @Column(name = "route_version_id")
    Long routeVersionId,

    @Column(name = "stop_order")
    Integer stopOrder
) implements Serializable {
}
