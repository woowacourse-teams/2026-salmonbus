package com.gustler.backend.api.board.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import lombok.EqualsAndHashCode;

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
