package com.gustler.backend.api.board.application;

import com.gustler.backend.api.board.domain.ForecastModel;
import java.util.Objects;

public record StoredPrediction(
    int targetStopOrder,
    String vehicleId,
    int sourceRowNumber,
    int horizonStops,
    double pFull,
    Double expectedSeats,
    ForecastModel model
) {

    public StoredPrediction {
        Objects.requireNonNull(model, "model은 null일 수 없습니다.");
    }
}
