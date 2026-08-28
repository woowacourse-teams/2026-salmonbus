package com.gustler.backend.collector.persistence.jpa;

import com.gustler.backend.collector.RouteStop;
import com.gustler.backend.collector.StopDirection;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

@Entity(name = "CollectorRouteStop")
@Table(name = "route_stop")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RouteStopJpaEntity implements Persistable<RouteStopJpaId> {

    @EmbeddedId
    private RouteStopJpaId id;

    private String stopId;

    private String name;

    @Enumerated(EnumType.STRING)
    private StopDirection direction;

    private boolean boardingAllowed;

    public RouteStopJpaEntity(
        final long routeVersionId,
        RouteStop stop
    ) {
        this.id = new RouteStopJpaId(routeVersionId, stop.stopOrder());
        this.stopId = stop.stopId();
        this.name = stop.name();
        this.direction = stop.direction();
        this.boardingAllowed = stop.boardingAllowed();
    }

    @Override
    public RouteStopJpaId getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return true;
    }
}
