package com.gustler.backend.api.board.application;

import com.gustler.backend.api.board.domain.ForecastModel;
import java.util.Objects;

/**
 * 저장된 예보 수치와 그 예보의 기준 식별을 탑승판 조회에 전달한다.
 */
public record StoredPrediction(
    int targetStopOrder,
    String vehicleId,
    int sourceRowNumber,
    int stopsToTarget,
    double seatFullChance,
    Double expectedSeats,
    ForecastModel model,
    String seatTarget,
    String probabilityTarget
) {
    public StoredPrediction(
        final int targetStopOrder,
        String vehicleId,
        final int sourceRowNumber,
        final int stopsToTarget,
        final double seatFullChance,
        Double expectedSeats,
        ForecastModel model
    ) {
        this(targetStopOrder, vehicleId, sourceRowNumber, stopsToTarget, seatFullChance, expectedSeats, model,
            "FIRST_POST_STOP_OBSERVATION", "FIRST_POST_STOP_ZERO");
    }

    public StoredPrediction {
        Objects.requireNonNull(model, "model은 null일 수 없습니다.");
    }
}
