package com.gustler.backend.api.board.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
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

    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SeatForecastJpaId that)) {
            return false;
        }
        return Objects.equals(vehicleObservationId, that.vehicleObservationId)
            && Objects.equals(targetStopOrder, that.targetStopOrder);
    }

    @Override
    public int hashCode() {
        return Objects.hash(vehicleObservationId, targetStopOrder);
    }
}
