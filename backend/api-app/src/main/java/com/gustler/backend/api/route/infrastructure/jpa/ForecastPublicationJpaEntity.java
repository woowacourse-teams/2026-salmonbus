package com.gustler.backend.api.route.infrastructure.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

@Entity(name = "RouteForecastPublicationJpaEntity")
@Table(name = "forecast_publication")
@Immutable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ForecastPublicationJpaEntity {

    @Id
    private Long id;

    @Column(name = "route_version_id", nullable = false)
    private Long routeVersionId;
}
