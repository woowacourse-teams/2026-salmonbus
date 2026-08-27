package com.gustler.backend.api.vehicle.dto;

import com.gustler.backend.api.vehicle.application.LiveVehicleOverview;
import java.util.List;
import java.util.Objects;

public record LiveVehicleResponse(
    String routeId,
    String referenceVersionId,
    ObservationResponse observation,
    List<VehicleResponse> vehicles
) {

    public LiveVehicleResponse {
        Objects.requireNonNull(routeId, "routeId는 null일 수 없습니다.");
        Objects.requireNonNull(referenceVersionId, "referenceVersionId는 null일 수 없습니다.");
        Objects.requireNonNull(observation, "observation은 null일 수 없습니다.");
        Objects.requireNonNull(vehicles, "vehicles는 null일 수 없습니다.");
        vehicles = List.copyOf(vehicles);
    }

    public static LiveVehicleResponse from(final LiveVehicleOverview overview) {
        return new LiveVehicleResponse(
            overview.routeId(),
            overview.referenceVersionId(),
            ObservationResponse.from(overview),
            overview.vehicles().stream()
                .map(VehicleResponse::from)
                .toList()
        );
    }
}
