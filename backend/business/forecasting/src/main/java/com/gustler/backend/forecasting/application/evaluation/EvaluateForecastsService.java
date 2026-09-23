package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.forecasting.api.evaluation.EvaluateForecasts;

import com.gustler.backend.forecasting.domain.evaluation.ArrivalCandidate;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalLabel;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalLabelResolver;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalObservationRepository;
import com.gustler.backend.forecasting.domain.evaluation.ForecastEvaluation;
import com.gustler.backend.forecasting.domain.evaluation.PendingForecast;
import com.gustler.backend.forecasting.domain.evaluation.ForecastEvaluationRepository;
import com.gustler.backend.forecasting.api.ForecastPolicy;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 예측 이후의 관측으로 평가하고, 확정 결과와 당일 보정을 같은 트랜잭션에 반영한다. */
@Component
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
public class EvaluateForecastsService implements EvaluateForecasts {

    private final ForecastEvaluationRepository evaluationRepository;
    private final SameDayFullOutcomesService sameDayFullOutcomesService;
    private final ArrivalObservationRepository arrivalObservationRepository;
    private final ForecastPolicy policy;
    private final Clock clock;

    public EvaluateForecastsService(
        ForecastEvaluationRepository evaluationRepository,
        SameDayFullOutcomesService sameDayFullOutcomesService,
        ArrivalObservationRepository arrivalObservationRepository,
        ForecastPolicy policy,
        Clock clock
    ) {
        this.evaluationRepository = evaluationRepository;
        this.sameDayFullOutcomesService = sameDayFullOutcomesService;
        this.arrivalObservationRepository = arrivalObservationRepository;
        this.policy = policy;
        this.clock = clock;
    }

    /**
     * 한 회차의 평가 결과와 당일 보정을 같은 트랜잭션에서 저장한다.
     *
     * <p>회차 안에서는 시각을 한 번만 읽는다. 회차 도중에 시각이 흐르면 앞뒤 예보가 서로 다른
     * 기준으로 기다림을 재게 된다.
     */
    @Transactional
    public void settleArrivalLabels() {
        Instant now = clock.instant();
        List<ForecastEvaluation> evaluations = new ArrayList<>();
        for (Long routeVersionId : evaluationRepository.findRouteVersionIdsWithPendingForecasts()) {
            evaluations.addAll(evaluationsOf(routeVersionId, now));
        }
        sameDayFullOutcomesService.record(evaluationRepository.settle(evaluations));
    }

    private List<ForecastEvaluation> evaluationsOf(
        final long routeVersionId,
        Instant now
    ) {
        List<PendingForecast> pending =
            evaluationRepository.findPending(routeVersionId, policy.pendingLimit());
        List<ForecastEvaluation> evaluations = new ArrayList<>();
        for (Map.Entry<String, List<PendingForecast>> byVehicle : groupByVehicle(pending).entrySet()) {
            evaluations.addAll(evaluationsOf(routeVersionId, byVehicle.getKey(), byVehicle.getValue(), now));
        }
        return evaluations;
    }

    /**
     * 차량 아이디가 없는 예보는 도착을 찾을 길이 없다. 그래도 판정은 도메인에 맡긴다.
     * 여기서 미리 걸러 내면 판정 규칙이 중복된다.
     */
    private Map<String, List<PendingForecast>> groupByVehicle(
        List<PendingForecast> pending
    ) {
        Map<String, List<PendingForecast>> byVehicle = new LinkedHashMap<>();
        for (PendingForecast forecast : pending) {
            byVehicle.computeIfAbsent(forecast.vehicleId(), vehicleId -> new ArrayList<>()).add(forecast);
        }
        return byVehicle;
    }

    private List<ForecastEvaluation> evaluationsOf(
        final long routeVersionId,
        String vehicleId,
        List<PendingForecast> forecasts,
        Instant now
    ) {
        List<ArrivalCandidate> candidates = readCandidates(routeVersionId, vehicleId, forecasts);
        List<ForecastEvaluation> evaluations = new ArrayList<>();
        for (PendingForecast forecast : forecasts) {
            ArrivalLabel label =
                ArrivalLabelResolver.resolve(forecast, observedAfterForecast(candidates, forecast), now);
            if (!(label instanceof ArrivalLabel.NotArrivedYet)) {
                evaluations.add(ForecastEvaluation.completed(
                    forecast.vehicleObservationId(), forecast.targetStopOrder(), label, now));
            }
        }
        return evaluations;
    }

    private List<ArrivalCandidate> readCandidates(
        final long routeVersionId,
        String vehicleId,
        List<PendingForecast> forecasts
    ) {
        if (vehicleId == null) {
            return List.of();
        }
        return arrivalObservationRepository.findAfter(
            routeVersionId, vehicleId, earliestGeneratedAt(forecasts), policy.arrivalLimit());
    }

    /**
     * 도착 후보를 볼 하한은 관측 시각이 아니라 <b>예보를 계산한 시각</b>이다.
     *
     * <p>예보가 관측보다 늦게 나올 수 있는데, 그 사이에 이미 DB 에 들어와 있던 도착 관측을 라벨로 쓰면
     * 예보를 내기 전에 답을 본 것이 된다.
     */
    private static Instant earliestGeneratedAt(
        List<PendingForecast> forecasts
    ) {
        Instant earliest = forecasts.getFirst().generatedAt();
        for (PendingForecast forecast : forecasts) {
            if (forecast.generatedAt().isBefore(earliest)) {
                earliest = forecast.generatedAt();
            }
        }
        return earliest;
    }

    private static List<ArrivalCandidate> observedAfterForecast(
        List<ArrivalCandidate> candidates,
        PendingForecast forecast
    ) {
        List<ArrivalCandidate> later = new ArrayList<>();
        for (ArrivalCandidate candidate : candidates) {
            if (candidate.observedAt().isAfter(forecast.generatedAt())) {
                later.add(candidate);
            }
        }
        return later;
    }
}
