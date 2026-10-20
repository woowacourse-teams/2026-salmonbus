package com.gustler.backend.api.vehicle.application;

import com.gustler.backend.api.vehicle.domain.ObservedVehicle;
import com.gustler.backend.api.vehicle.domain.VehicleObservationState;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 최신 차량 관측과 결과의 유효 시간·캐시 정책을 함께 전달한다.
 */
public record LiveVehicleOverview(
    String routeId,
    String referenceVersionId,
    VehicleObservationState state,
    OffsetDateTime observedAt,
    OffsetDateTime staleAt,
    List<ObservedVehicle> vehicles,
    Duration cacheMaxAge
) {
    public LiveVehicleOverview {
        Objects.requireNonNull(routeId, "routeId는 null일 수 없습니다.");
        Objects.requireNonNull(referenceVersionId, "referenceVersionId는 null일 수 없습니다.");
        Objects.requireNonNull(state, "state는 null일 수 없습니다.");
        Objects.requireNonNull(vehicles, "vehicles는 null일 수 없습니다.");
        Objects.requireNonNull(cacheMaxAge, "cacheMaxAge는 null일 수 없습니다.");
        vehicles = List.copyOf(vehicles);
    }
}
