package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.diagnostics.WorkerOperationLog;
import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.domain.evaluation.ForecastEvaluation;
import com.gustler.backend.forecasting.domain.evaluation.ForecastEvaluationRepository;
import com.gustler.backend.forecasting.domain.evaluation.ScoringState;
import com.gustler.backend.forecasting.domain.evaluation.SettledEvaluation;
import com.gustler.backend.forecasting.domain.evaluation.SettledForecast;
import com.gustler.backend.forecasting.domain.statistics.DemandSample;
import com.gustler.backend.forecasting.domain.statistics.DemandSampleRepository;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 평가 결과, 통계 입력, 당일 보정을 같은 트랜잭션에 반영한다. */
@Component
public class ForecastEvaluationWriter {

    private final ForecastEvaluationRepository evaluations;
    private final RouteDataQualityAccess quality;
    private final SameDayFullOutcomesService outcomes;
    private final DemandSampleRepository samples;

    public ForecastEvaluationWriter(
        ForecastEvaluationRepository evaluations,
        RouteDataQualityAccess quality,
        SameDayFullOutcomesService outcomes,
        DemandSampleRepository samples
    ) {
        this.evaluations = evaluations;
        this.quality = quality;
        this.outcomes = outcomes;
        this.samples = samples;
    }

    @Transactional
    public List<SettledForecast> complete(List<ForecastEvaluation> completed) {
        if (completed.isEmpty()) {
            return List.of();
        }
        if (completed.stream().anyMatch(evaluation -> evaluation.state() == ScoringState.PENDING)) {
            throw new IllegalArgumentException("완료된 평가만 저장할 수 있다");
        }
        List<SettledForecast> newlySettled = WorkerOperationLog.measure("settlement_save", "all", () -> settle(completed));
        if (!newlySettled.isEmpty()) {
            WorkerOperationLog.run("same_day_record", "all", () -> outcomes.record(newlySettled));
        }
        return newlySettled;
    }

    private List<SettledForecast> settle(List<ForecastEvaluation> completed) {
        List<Long> observationIds = completed.stream().map(ForecastEvaluation::vehicleObservationId).distinct().toList();
        evaluations.findRouteIdsForObservations(observationIds).stream().distinct().sorted()
            .forEach(quality::lockByRoute);

        List<SettledForecast> settled = new ArrayList<>();
        List<DemandSample> demandSamples = new ArrayList<>();
        for (SettledEvaluation result : evaluations.settle(completed)) {
            result.calibrationOutcome().ifPresent(settled::add);
            demandSampleOf(result).ifPresent(demandSamples::add);
        }
        if (!demandSamples.isEmpty()) {
            samples.record(demandSamples);
        }
        return List.copyOf(settled);
    }

    private static Optional<DemandSample> demandSampleOf(SettledEvaluation result) {
        if (result.state() != ScoringState.SETTLED) {
            return Optional.empty();
        }
        return DemandSample.fromSettlement(result.routeVersionId(), result.vehicleObservationId(),
            result.arrivalObservationId(), result.predictionVehicleId(), result.targetStopOrder(),
            result.stopsToTarget(), result.targetBoardingAllowed(), result.arrivedAt(), result.scoredAt(),
            result.predictionRemainingSeats(), result.seatsOnArrival());
    }
}
