package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.domain.evaluation.ForecastEvaluation;
import com.gustler.backend.forecasting.domain.evaluation.ForecastEvaluationRepository;
import com.gustler.backend.forecasting.domain.evaluation.ScoringState;
import com.gustler.backend.forecasting.domain.evaluation.SettledForecast;
import com.gustler.backend.observations.api.CollectionInput;
import com.gustler.backend.observations.api.CollectionInputs;
import java.util.List;
import java.util.ArrayList;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 도착 입력을 확정하고 평가 결과와 당일 보정을 같은 트랜잭션에 반영한다. */
@Component
public class ForecastEvaluationWriter {

    private final ForecastEvaluationRepository evaluations;
    private final RouteDataQualityAccess quality;
    private final CollectionInputs collectionInputs;
    private final SameDayFullOutcomesService outcomes;

    public ForecastEvaluationWriter(
        ForecastEvaluationRepository evaluations,
        RouteDataQualityAccess quality,
        CollectionInputs collectionInputs,
        SameDayFullOutcomesService outcomes
    ) {
        this.evaluations = evaluations;
        this.quality = quality;
        this.collectionInputs = collectionInputs;
        this.outcomes = outcomes;
    }

    @Transactional
    public List<SettledForecast> complete(List<ForecastEvaluation> completed) {
        if (completed.isEmpty()) {
            return List.of();
        }
        if (completed.stream().anyMatch(evaluation -> evaluation.state() == ScoringState.PENDING)) {
            throw new IllegalArgumentException("완료된 평가만 저장할 수 있다");
        }
        List<Long> observationIds = completed.stream().map(ForecastEvaluation::vehicleObservationId).distinct().toList();
        evaluations.findRouteIdsForObservations(observationIds).stream().distinct().sorted()
            .forEach(quality::lockByRoute);

        List<SettledForecast> settled = new ArrayList<>();
        for (ForecastEvaluation evaluation : completed) {
            // 이미 완료됐거나 현재 품질 조건에 맞지 않으면 도착 배치도 확정하지 않는다.
            if (!evaluations.canComplete(evaluation)) {
                continue;
            }
            Long arrivalId = evaluation.result().arrivalObservationId();
            if (arrivalId != null) {
                CollectionInput input = collectionInputs.lockForObservation(arrivalId);
                collectionInputs.confirmInput(input.batchId(), input.attemptNumber(), evaluation.scoredAt());
            }
            settled.addAll(evaluations.settle(List.of(evaluation)));
        }
        List<SettledForecast> newlySettled = List.copyOf(settled);
        if (!newlySettled.isEmpty()) {
            outcomes.record(newlySettled);
        }
        return newlySettled;
    }
}
