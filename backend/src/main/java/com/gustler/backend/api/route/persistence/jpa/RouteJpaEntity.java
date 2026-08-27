package com.gustler.backend.api.route.persistence.jpa;

import com.gustler.backend.api.route.domain.Route;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "route")
public class RouteJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_route_id", nullable = false, length = 30)
    private String publicRouteId;

    @Column(name = "source_id", nullable = false, length = 40)
    private String sourceId;

    @Column(name = "source_route_id", nullable = false, length = 30)
    private String sourceRouteId;

    @Column(name = "display_name", nullable = false, length = 40)
    private String displayName;

    @Column(name = "start_stop_name", nullable = false, length = 60)
    private String startStopName;

    @Column(name = "end_stop_name", nullable = false, length = 60)
    private String endStopName;

    protected RouteJpaEntity() {
    }

    public Route toDomain() {
        return new Route(
            sourceRouteId,
            displayName,
            startStopName,
            endStopName
        );
    }
}
