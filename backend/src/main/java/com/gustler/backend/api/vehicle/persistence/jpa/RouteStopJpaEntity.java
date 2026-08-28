package com.gustler.backend.api.vehicle.persistence.jpa;

import com.gustler.backend.api.vehicle.domain.VehicleDirection;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "route_stop")
public class RouteStopJpaEntity {

    @EmbeddedId
    private RouteStopJpaId id;

    @Column(name = "stop_id", nullable = false, length = 20)
    private String stopId;

    @Column(name = "name", nullable = false, length = 60)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 4)
    private VehicleDirection direction;

    protected RouteStopJpaEntity() {
    }

    public String name() {
        return name;
    }

    public VehicleDirection direction() {
        return direction;
    }
}
