package com.gustler.backend.api.board.dto;

import com.gustler.backend.api.board.domain.ApproachingVehicle;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 접근 차량의 식별·남은 정류장 수·좌석 예보를 공개 탑승판 필드로 변환한 값이다.
 */
@Schema(requiredProperties = {"vehicleId", "remainingStops", "forecast"})
public record ApproachingVehicleResponse(
    @Schema(description = "관측된 차량 ID. 제공되지 않으면 null", nullable = true)
    String vehicleId,
    @Schema(
        description = "대상 정류장 순번 − 버스가 있는 정류장 순번(GBIS stationSeq). 0이면 이 정류장에 도착 중이다",
        minimum = "0",
        maximum = "12"
    )
    int remainingStops,
    @Schema(description = "좌석 예보. 상태가 UNAVAILABLE이면 확률과 기대 좌석은 생략")
    ForecastResponse forecast
) {
    static ApproachingVehicleResponse from(
        ApproachingVehicle vehicle
    ) {
        return new ApproachingVehicleResponse(
            vehicle.vehicleId(),
            vehicle.remainingStops(),
            ForecastResponse.from(vehicle.forecast())
        );
    }
}
