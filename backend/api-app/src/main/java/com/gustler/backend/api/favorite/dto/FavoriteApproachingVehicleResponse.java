package com.gustler.backend.api.favorite.dto;

import com.gustler.backend.api.board.dto.ForecastResponse;
import com.gustler.backend.api.favorite.application.FavoriteOverview.Vehicle;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"vehicleId", "horizonStops", "forecast", "eta"})
public record FavoriteApproachingVehicleResponse(
    @Schema(description = "watch와 같은 관측 차량 ID. 제공되지 않으면 null", nullable = true)
    String vehicleId,
    @Schema(description = "watch와 같은 대상 정류장까지 남은 정류장 수", minimum = "0")
    int horizonStops,
    @Schema(description = "watch와 같은 승차 시점 좌석 예보")
    ForecastResponse forecast,
    @Schema(description = "차량 ID로 확인한 대상 정류장 ETA")
    FavoriteEtaResponse eta
) {
    static FavoriteApproachingVehicleResponse from(Vehicle vehicle) {
        return new FavoriteApproachingVehicleResponse(vehicle.watch().vehicleId(), vehicle.watch().horizonStops(),
            ForecastResponse.from(vehicle.watch().forecast()), FavoriteEtaResponse.from(vehicle.eta()));
    }
}
