package com.gustler.backend.collector.persistence.jpa;

import jakarta.persistence.Embeddable;
import java.io.Serializable;

@Embeddable
public record RouteStopJpaId(
    Long routeVersionId,
    Integer stopOrder
) implements Serializable {
}
