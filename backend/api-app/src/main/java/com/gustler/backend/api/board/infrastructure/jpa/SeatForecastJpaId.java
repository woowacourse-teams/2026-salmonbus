package com.gustler.backend.api.board.infrastructure.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import lombok.EqualsAndHashCode;

/**
 * 관측과 대상 정류장 방문으로 예보 행을 구분하는 JPA 복합 식별자다.
 */
@Embeddable
@EqualsAndHashCode
public class SeatForecastJpaId implements Serializable {
    @Column(name = "vehicle_observation_id", nullable = false)
    private Long vehicleObservationId;

    @Column(name = "target_stop_order", nullable = false)
    private Integer targetStopOrder;

    protected SeatForecastJpaId() {
    }

    public Integer targetStopOrder() {
        return targetStopOrder;
    }
}
