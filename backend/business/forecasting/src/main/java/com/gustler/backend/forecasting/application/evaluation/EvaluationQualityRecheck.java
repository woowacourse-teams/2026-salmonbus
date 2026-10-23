package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.forecasting.application.evaluation.EvaluateForecastsService.RouteInput;
import com.gustler.backend.forecasting.application.evaluation.EvaluationWorkStore.Key;
import com.gustler.backend.forecasting.application.evaluation.EvaluationWorkStore.Restored;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalCandidate;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalLabel;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalLabelResolver;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalObservationQuery;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationResult;
import com.gustler.backend.forecasting.domain.evaluation.ForecastEvaluation;
import com.gustler.backend.forecasting.domain.evaluation.PendingForecast;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 품질 변경과 겹친 회차에만 노선 품질 잠금 안에서 DB 자료로 기존 계산을 다시 확인한다. */
@Component
public class EvaluationQualityRecheck {
    private final EvaluationWorkStore work;
    private final ArrivalObservationQuery arrivals;
    private final int arrivalLimit;

    public EvaluationQualityRecheck(EvaluationWorkStore work, ArrivalObservationQuery arrivals,
        @Value("${forecast.arrival-limit:400}") final int arrivalLimit) {
        if (arrivalLimit < 1) {
            throw new IllegalArgumentException("후속 관측 조회 한도는 양수여야 한다");
        }
        this.work = work;
        this.arrivals = arrivals;
        this.arrivalLimit = arrivalLimit;
    }

    /**
     * 계산 후 작업·품질 상태를 다시 읽어 여전히 유효한 평가만 남긴다. 바뀐 결과는 다음 처리로 미룬다.
     */
    public List<ForecastEvaluation> unchanged(RouteInput input,
        List<ForecastEvaluation> results) {
        if (results.isEmpty()) {
            return List.of();
        }
        Map<Key, PendingForecast> selected = new LinkedHashMap<>();
        Map<String, Instant> earliest = new HashMap<>();
        for (PendingForecast forecast : input.selected()) {
            selected.put(key(forecast.vehicleObservationId(), forecast.targetStopOrder()), forecast);
            earliest.merge(forecast.vehicleId(), forecast.generatedAt(),
                EvaluationQualityRecheck::earlierOf);
        }
        List<Long> ids = results.stream()
            .map(ForecastEvaluation::vehicleObservationId)
            .distinct()
            .toList();
        Map<Key, PendingForecast> current = new HashMap<>();
        for (int from = 0; from < ids.size(); from += 100) {
            for (
                Restored restored : work.restore(input.version(),
                    ids.subList(from, Math.min(from + 100, ids.size())))) {
                restored.forecast().ifPresent(forecast -> current.put(restored.key(), forecast));
            }
        }
        Map<String, List<ArrivalCandidate>> byVehicle = new HashMap<>();
        List<ForecastEvaluation> safe = new ArrayList<>();
        for (ForecastEvaluation result : results) {
            Key key = key(result.vehicleObservationId(), result.targetStopOrder());
            PendingForecast forecast = selected.get(key);
            // 조사 중인 차량과 제외된 편도의 예보 상세는 DB에서 복원되지 않는다.
            if (forecast == null || !forecast.equals(current.get(key))) {
                continue;
            }
            List<ArrivalCandidate> candidates = readCandidates(input, forecast, earliest, byVehicle);
            // 대기 시간 초과 판정에는 원래 계산 시각을 사용한다.
            ArrivalLabel label = ArrivalLabelResolver.resolve(forecast, candidates, result.scoredAt());
            if (label.settles() && result.result().equals(EvaluationResult.from(label))) {
                safe.add(result);
            }
        }
        return List.copyOf(safe);
    }

    private static Key key(final long observation, final int target) {
        return new Key(observation, target);
    }

    private static Instant earlierOf(Instant left, Instant right) {
        if (left.isBefore(right)) {
            return left;
        }
        return right;
    }

    private List<ArrivalCandidate> readCandidates(
        RouteInput input,
        PendingForecast forecast,
        Map<String, Instant> earliest,
        Map<String, List<ArrivalCandidate>> byVehicle
    ) {
        if (!forecast.hasVehicleId()) {
            return List.of();
        }
        String vehicle = forecast.vehicleId();
        List<ArrivalCandidate> candidates = byVehicle.get(vehicle);
        if (candidates != null) {
            return candidates;
        }
        candidates = arrivals.findAfter(input.version(), vehicle, earliest.get(vehicle), arrivalLimit);
        if (candidates != null) {
            byVehicle.put(vehicle, candidates);
        }
        return candidates;
    }
}
