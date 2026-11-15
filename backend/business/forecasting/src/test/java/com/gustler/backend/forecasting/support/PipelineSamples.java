package com.gustler.backend.forecasting.support;

import com.gustler.backend.forecasting.api.pipeline.ForecastOutcome;
import com.gustler.backend.forecasting.api.pipeline.ForecastPublished;
import com.gustler.backend.forecasting.api.pipeline.PassClosed;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 새 예보 경로 사건의 시험용 값을 만든다. FK가 없는 테이블이라 노선·관측 행 없이 번호만 맞춘다. */
public final class PipelineSamples {
    public static final long ROUTE_ID = 7L;
    public static final long ROUTE_VERSION_ID = 70L;
    public static final long MODEL_DEPLOYMENT_ID = 3L;

    private PipelineSamples() {
    }

    public static ForecastPublished.VehicleForecast vehicle(final long observationId, final int rowNumber) {
        return new ForecastPublished.VehicleForecast(observationId, rowNumber, "bus-" + observationId, 4,
            observationId - 100, observationId - 50, false, 45, false, new int[] {5, 6, 7},
            new double[] {12.5, 10.25, 8.0}, new double[] {0.1, 0.2, 0.3});
    }

    public static ForecastPublished published(
        LocalDate sourceDate,
        final long batchId,
        Instant receivedAt,
        List<ForecastPublished.VehicleForecast> vehicles
    ) {
        return new ForecastPublished(sourceDate, batchId, ROUTE_ID, ROUTE_VERSION_ID, receivedAt,
            receivedAt.plusSeconds(2), MODEL_DEPLOYMENT_ID, outcomeOf(vehicles), vehicles);
    }

    private static ForecastOutcome outcomeOf(List<ForecastPublished.VehicleForecast> vehicles) {
        if (vehicles.isEmpty()) {
            return ForecastOutcome.EMPTY;
        }
        return ForecastOutcome.PUBLISHED;
    }

    public static ForecastPublished skipped(
        LocalDate sourceDate,
        final long batchId,
        Instant receivedAt,
        ForecastOutcome outcome
    ) {
        return new ForecastPublished(sourceDate, batchId, ROUTE_ID, ROUTE_VERSION_ID, receivedAt,
            receivedAt.plusSeconds(95), null, outcome, List.of());
    }

    public static PassClosed pass(
        LocalDate sourceDate,
        final long tripStart,
        final int stopOrder,
        Instant closedAt
    ) {
        return new PassClosed(sourceDate, ROUTE_ID, tripStart, stopOrder, ROUTE_VERSION_ID, "bus-" + tripStart,
            tripStart + 1, sourceDate, tripStart + 10, 20, closedAt.minusSeconds(90), closedAt.minusSeconds(89),
            tripStart + 11, closedAt.minusSeconds(60), tripStart + 12, 18, closedAt.minusSeconds(30),
            closedAt.minusSeconds(29), closedAt.minusSeconds(30), tripStart + 13, tripStart + 14, closedAt);
    }
}
