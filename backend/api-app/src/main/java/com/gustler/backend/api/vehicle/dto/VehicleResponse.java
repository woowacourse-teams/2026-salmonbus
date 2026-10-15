package com.gustler.backend.api.vehicle.dto;

import com.gustler.backend.api.vehicle.domain.ObservedVehicle;
import com.gustler.backend.api.vehicle.domain.VehicleDirection;
import com.gustler.backend.api.vehicle.domain.VehiclePhase;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Objects;

@Schema(requiredProperties = {"vehicleId", "direction", "currentStopSequence", "stopId",
    "stopName", "phase", "seat"})
public record VehicleResponse(
    @Schema(description = "상류 차량 식별자. 제공되지 않으면 null", nullable = true)
    String vehicleId,
    @Schema(description = "현재 노선 판본의 UP 또는 DOWN")
    VehicleDirection direction,
    @Schema(description = "현재 정류장 순번")
    int currentStopSequence,
    @Schema(description = "현재 정류장 ID")
    String stopId,
    @Schema(description = "현재 정류장 이름")
    String stopName,
    @Schema(description = "상류 운행 상태를 해석한 ARRIVING, DEPARTED 또는 IN_TRANSIT")
    VehiclePhase phase,
    @Schema(description = "좌석 수가 알려져 있으면 EXACT, 숨기거나 미제공이면 UNKNOWN")
    SeatResponse seat
) {

    public VehicleResponse {
        Objects.requireNonNull(direction, "direction은 null일 수 없습니다.");
        Objects.requireNonNull(stopId, "stopId는 null일 수 없습니다.");
        Objects.requireNonNull(stopName, "stopName은 null일 수 없습니다.");
        Objects.requireNonNull(phase, "phase는 null일 수 없습니다.");
        Objects.requireNonNull(seat, "seat는 null일 수 없습니다.");
    }

    public static VehicleResponse from(ObservedVehicle vehicle) {
        return new VehicleResponse(
            vehicle.vehicleId(),
            vehicle.direction(),
            vehicle.currentStopSequence(),
            vehicle.stopId(),
            vehicle.stopName(),
            vehicle.phase(),
            SeatResponse.from(vehicle.seat())
        );
    }
}
