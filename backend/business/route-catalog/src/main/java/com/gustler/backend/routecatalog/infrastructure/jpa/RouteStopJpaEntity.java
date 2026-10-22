package com.gustler.backend.routecatalog.infrastructure.jpa;

import com.gustler.backend.routecatalog.domain.RouteStop;
import com.gustler.backend.routecatalog.domain.StopDirection;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * 노선 판본 안의 정류장 방문을 매핑한다.
 * 같은 정류장의 반복 방문은 방문 순번으로 구분한다.
 */
@Entity(name = "RouteStop")
@Table(name = "route_stop")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RouteStopJpaEntity implements Persistable<RouteStopJpaId> {
    @EmbeddedId
    private RouteStopJpaId id;

    @Column(name = "stop_id")
    private String stopId;

    private String name;

    @Enumerated(EnumType.STRING)
    private StopDirection direction;

    @Column(name = "boarding_allowed")
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
