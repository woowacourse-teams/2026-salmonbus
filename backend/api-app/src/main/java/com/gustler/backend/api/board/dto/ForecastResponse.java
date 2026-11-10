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
    @Schema(description = "AVAILABLE 또는 UNAVAILABLE") Status status,
    @Schema(description = "좌석이 남을 확률. 개인의 탑승 확률이 아님. probabilityTarget으로 관측 기준 구분. 0~1; UNAVAILABLE이면 생략", minimum = "0", maximum = "1", requiredMode = RequiredMode.NOT_REQUIRED) Double seatAvailableProbability,
    @Schema(description = "좌석 점예측. seatTarget으로 하차 후 추정값과 기존 관측 기준을 구분. 값이 없으면 생략", minimum = "0", requiredMode = RequiredMode.NOT_REQUIRED) Double expectedSeats,
    @Schema(description = "좌석 목표: FIRST_POST_STOP_OBSERVATION 또는 AFTER_ALIGHTING_ESTIMATE", allowableValues = {
        "FIRST_POST_STOP_OBSERVATION",
        "AFTER_ALIGHTING_ESTIMATE" }, requiredMode = RequiredMode.NOT_REQUIRED) String seatTarget,
    @Schema(description = "확률 목표: FIRST_POST_STOP_ZERO 또는 DEPARTURE_ZERO", allowableValues = { "FIRST_POST_STOP_ZERO",
        "DEPARTURE_ZERO" }, requiredMode = RequiredMode.NOT_REQUIRED) String probabilityTarget,
    @Schema(description = "하차 후 빈 좌석 추정값. 하차 반영 좌석 모델에서만 제공", minimum = "0", maximum = "70", requiredMode = RequiredMode.NOT_REQUIRED) Double afterAlightingSeatEstimate,
    @Schema(description = "목표 정류장에서 출발할 때 0석일 확률. 하차 반영 좌석 모델에서만 제공", minimum = "0", maximum = "1", requiredMode = RequiredMode.NOT_REQUIRED) Double departureFullProbability
){
    public ForecastResponse(Status status, Double seatAvailableProbability, Double expectedSeats) {
        this(status, seatAvailableProbability, expectedSeats, null, null, null, null);
    }

    public enum Status {
        AVAILABLE, UNAVAILABLE
    }

    private static Double afterAlightingEstimate(VehicleForecast.Available available) {
        if ("AFTER_ALIGHTING_ESTIMATE".equals(available.seatTarget())) {
            return available.expectedSeats();
        }
        return null;
    }

    private static Double departureFullProbability(VehicleForecast.Available available) {
        if ("DEPARTURE_ZERO".equals(available.probabilityTarget())) {
            return available.departureFullProbability();
        }
        return null;
    }

    public static ForecastResponse from(VehicleForecast forecast) {
        return switch (forecast) {
        case VehicleForecast.Available available -> new ForecastResponse(
            Status.AVAILABLE, available.seatAvailableProbability(), available.expectedSeats(),
            available.seatTarget(), available.probabilityTarget(),
            afterAlightingEstimate(available),
            departureFullProbability(available));
        case VehicleForecast.Unavailable ignored -> new ForecastResponse(Status.UNAVAILABLE, null, null);
        };
    }
}
