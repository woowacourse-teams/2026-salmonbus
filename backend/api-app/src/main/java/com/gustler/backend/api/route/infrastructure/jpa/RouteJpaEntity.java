package com.gustler.backend.api.route.infrastructure.jpa;

import com.gustler.backend.api.route.domain.Route;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 공개 노선 식별과 표시 정보를 API 조회 모델로 옮기는 JPA 매핑이다.
 */
@Entity
@Table(name = "route")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RouteJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String publicRouteId;

    @Column(nullable = false)
    private String sourceId;

    @Column(nullable = false)
    private String sourceRouteId;

    @Column(nullable = false)
    private String displayName;

    @Column(nullable = false)
    private String startStopName;

    @Column(nullable = false)
    private String endStopName;

    Route toDomain() {
        return new Route(
            sourceRouteId,
            displayName,
            startStopName,
            endStopName
        );
    }
}
