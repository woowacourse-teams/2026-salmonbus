package com.gustler.backend.api.board.infrastructure.jpa;

import com.gustler.backend.api.board.domain.BoardDirection;
import com.gustler.backend.api.board.domain.BoardStop;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * 노선 판본 안의 정류장 방문을 매핑한다.
 * 같은 정류장의 반복 방문은 방문 순번으로 구분한다.
 */
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
