package com.gustler.backend.api.vehicle.dto;

import com.gustler.backend.api.vehicle.application.LiveVehicleOverview;
import com.gustler.backend.api.vehicle.domain.VehicleObservationState;
import java.time.OffsetDateTime;
import java.util.Objects;

public record ObservationResponse(
    VehicleObservationState state,
    OffsetDateTime observedAt,
    OffsetDateTime staleAt
) {

    public ObservationResponse {
        Objects.requireNonNull(state, "state는 null일 수 없습니다.");
    }

    public static ObservationResponse from(final LiveVehicleOverview overview) {
        return new ObservationResponse(
            overview.state(),
            overview.observedAt(),
            overview.staleAt()
        );
    }
}
