package com.gustler.backend.api.vehicle.dto;

import com.gustler.backend.api.vehicle.application.LiveVehicleOverview;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Objects;

@Schema(requiredProperties = {"routeId", "referenceVersionId", "observation", "vehicles"})
public record LiveVehicleResponse(
    @Schema(description = "조회한 9자리 GBIS 노선 ID")
    String routeId,
    @Schema(description = "차량 관측을 해석한 노선 판본 ID")
    String referenceVersionId,
    @Schema(description = "차량 관측 상태와 시각. UNKNOWN이어도 객체는 항상 존재")
    ObservationResponse observation,
    @Schema(description = "응답에 포함된 차량 목록. 관측이 없거나 불확실하면 빈 배열")
    List<VehicleResponse> vehicles
) {

    public LiveVehicleResponse {
        Objects.requireNonNull(routeId, "routeId는 null일 수 없습니다.");
        Objects.requireNonNull(referenceVersionId, "referenceVersionId는 null일 수 없습니다.");
        Objects.requireNonNull(observation, "observation은 null일 수 없습니다.");
        Objects.requireNonNull(vehicles, "vehicles는 null일 수 없습니다.");
        vehicles = List.copyOf(vehicles);
    }

    public static LiveVehicleResponse from(LiveVehicleOverview overview) {
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
