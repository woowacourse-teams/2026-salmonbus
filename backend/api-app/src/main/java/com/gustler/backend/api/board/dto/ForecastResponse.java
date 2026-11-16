package com.gustler.backend.api.board.dto;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.gustler.backend.api.board.domain.VehicleForecast;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 승차 시점 좌석 예보의 제공 가능 상태와 수치를 공개 응답으로 전달한다.
 */
@JsonInclude(Include.NON_NULL)
@Schema(requiredProperties = { "status" })
public record ForecastResponse(
    @Schema(description = "AVAILABLE 또는 UNAVAILABLE")
    Status status,
    @Schema(
        description = "좌석이 남을 확률. 개인의 탑승 확률이 아님. 관측 기준은 probabilityTarget. 0~1; UNAVAILABLE이면 생략",
        minimum = "0",
        maximum = "1",
        requiredMode = RequiredMode.NOT_REQUIRED
    )
    Double seatAvailableProbability,
    @Schema(
        description = "좌석 점예측. 관측 기준은 seatTarget. 값이 없으면 생략",
        minimum = "0",
        requiredMode = RequiredMode.NOT_REQUIRED
    )
    Double expectedSeats,
    @Schema(
        description = "좌석 목표: AFTER_ALIGHTING_ESTIMATE",
        allowableValues = { "AFTER_ALIGHTING_ESTIMATE" },
        requiredMode = RequiredMode.NOT_REQUIRED
    )
    String seatTarget,
    @Schema(
        description = "확률 목표: DEPARTURE_ZERO",
        allowableValues = { "DEPARTURE_ZERO" },
        requiredMode = RequiredMode.NOT_REQUIRED
    )
    String probabilityTarget,
    @Schema(
        description = "하차 후 빈 좌석 추정값. 값이 없으면 생략",
        minimum = "0",
        maximum = "70",
        requiredMode = RequiredMode.NOT_REQUIRED
    )
    Double afterAlightingSeatEstimate,
    @Schema(
        description = "목표 정류장에서 출발할 때 0석일 확률. UNAVAILABLE이면 생략",
        minimum = "0",
        maximum = "1",
        requiredMode = RequiredMode.NOT_REQUIRED
    )
    Double departureFullProbability
) {
    public ForecastResponse(Status status, Double seatAvailableProbability, Double expectedSeats) {
        this(status, seatAvailableProbability, expectedSeats, null, null, null, null);
    }

    public enum Status {
        AVAILABLE, UNAVAILABLE
    }

    public static ForecastResponse from(VehicleForecast forecast) {
        return switch (forecast) {
        case VehicleForecast.Available available -> new ForecastResponse(
            Status.AVAILABLE,
            available.seatAvailableProbability(),
            available.expectedSeats(),
            available.seatTarget(),
            available.probabilityTarget(),
            available.expectedSeats(),
            available.departureFullProbability());
        case VehicleForecast.Unavailable ignored -> new ForecastResponse(Status.UNAVAILABLE, null, null);
        };
    }
}
