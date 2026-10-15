package com.gustler.backend.api.board.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gustler.backend.api.board.domain.VehicleForecast;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(requiredProperties = {"status"})
public record ForecastResponse(
    @Schema(description = "AVAILABLE 또는 UNAVAILABLE")
    Status status,
    @Schema(description = "좌석 이용 가능 확률. 0~1; UNAVAILABLE이면 필드 생략", minimum = "0", maximum = "1", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    Double seatAvailableProbability,
    @Schema(description = "기대 잔여 좌석 수. 값이 없거나 UNAVAILABLE이면 필드 생략", minimum = "0", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    Double expectedSeats
) {

    public enum Status { AVAILABLE, UNAVAILABLE }

    static ForecastResponse from(VehicleForecast forecast) {
        return switch (forecast) {
            case VehicleForecast.Available available -> new ForecastResponse(
                Status.AVAILABLE, available.seatAvailableProbability(), available.expectedSeats());
            case VehicleForecast.Unavailable ignored -> new ForecastResponse(Status.UNAVAILABLE, null, null);
        };
    }
}
