package com.gustler.backend.api.board.persistence.jpa;

import com.gustler.backend.api.board.domain.BoardDirection;
import com.gustler.backend.api.board.domain.BoardStop;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity(name = "BoardRouteStopJpaEntity")
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
    private BoardDirection direction;

    @Column(name = "boarding_allowed", nullable = false)
    private Boolean boardingAllowed;

    protected RouteStopJpaEntity() {
    }

    public BoardStop toDomain() {
        return new BoardStop(
            id.stopOrder(),
            stopId,
            name,
            direction,
            boardingAllowed
        );
    }
}
