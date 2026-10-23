package com.gustler.backend.api.route.infrastructure.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * API 조회가 발행 여부와 기준 판본을 확인할 때 사용하는 예보 발행 매핑이다.
 */
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
