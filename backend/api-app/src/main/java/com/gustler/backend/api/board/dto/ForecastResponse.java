package com.gustler.backend.api.board.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.gustler.backend.api.board.domain.VehicleForecast;
import com.gustler.backend.api.board.domain.VehicleForecast.Available;
import com.gustler.backend.api.board.domain.VehicleForecast.Unavailable;
import com.gustler.backend.api.board.dto.ForecastResponse.Status;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/**
 * 승차 시점 좌석 예보의 제공 가능 상태와 수치를 공개 응답으로 전달한다.
 */
@JsonInclude(Include.NON_NULL)
@Schema(requiredProperties = {"status"})
public record ForecastResponse(
    @Schema(description = "AVAILABLE 또는 UNAVAILABLE")
    Status status,
    @Schema(description = "좌석 이용 가능 확률. 0~1; UNAVAILABLE이면 필드 생략", minimum = "0", maximum = "1",
        requiredMode = RequiredMode.NOT_REQUIRED)
    Double seatAvailableProbability,
    @Schema(description = "기대 잔여 좌석 수. 값이 없거나 UNAVAILABLE이면 필드 생략", minimum = "0",
        requiredMode = RequiredMode.NOT_REQUIRED)
    Double expectedSeats
) {
    public enum Status {
        AVAILABLE, UNAVAILABLE
    }

    public static ForecastResponse from(VehicleForecast forecast) {
        return switch (forecast) {
            case Available available -> new ForecastResponse(
                Status.AVAILABLE, available.seatAvailableProbability(), available.expectedSeats());
            case Unavailable ignored -> new ForecastResponse(Status.UNAVAILABLE, null, null);
        };
    }
}
