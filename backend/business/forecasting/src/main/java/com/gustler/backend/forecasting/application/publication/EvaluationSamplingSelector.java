package com.gustler.backend.forecasting.application.publication;

import com.gustler.backend.forecasting.domain.evaluation.EvaluationSamplingPoint;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationSamplingPolicy;
import com.gustler.backend.forecasting.domain.publication.ForecastPublication;
import com.gustler.backend.forecasting.domain.publication.SeatForecast;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 실제 커밋 후에만 선택 기준을 기억한다. 동시 선택은 상세 저장을 더 남기는 쪽으로 처리한다. */
@Component
public class EvaluationSamplingSelector {
    private static final int MAX_VEHICLES = 10_000;
    private final boolean enabled;
    private final Set<String> routes;
    private final EvaluationSamplingPolicy policy;
    private final Map<Key, EvaluationSamplingPoint> committed = new LinkedHashMap<>();

    public EvaluationSamplingSelector(
        @Value("${forecast.evaluation-sampling.enabled:false}") final boolean enabled,
        @Value("${forecast.evaluation-sampling.route-ids:}") String routeIds,
        @Value("${forecast.evaluation-sampling.interval:60s}") Duration interval
    ) {
        this.enabled = enabled;
        this.routes = Arrays.stream(routeIds.split(","))
            .map(String::strip)
            .filter(value -> !value.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
        this.policy = new EvaluationSamplingPolicy(interval);
    }

    /**
     * 현재 커밋된 표본 기준으로 발행 예측을 선택한다. 이 호출만으로 다음 표본 기준을 확정하지 않는다.
     */
    public synchronized Selection select(ForecastPublication publication, List<EvaluationSamplingSource> sources) {
        if (!enabled || publication.predictions().isEmpty()) {
            return new Selection(publication, List.of());
        }
        Map<Long, Boolean> decisions = new HashMap<>();
        List<EvaluationSamplingPoint> advances = new ArrayList<>();
        Set<Long> predicted = publication.predictions()
            .stream()
            .map(SeatForecast::vehicleObservationId)
            .collect(Collectors.toSet());
        for (EvaluationSamplingSource source : sources) {
            EvaluationSamplingPoint current = source.point();
            if (shouldSkip(source, current, predicted)) {
                continue;
            }
            if (!belongsToPublication(current, publication)) {
                throw new IllegalArgumentException("평가 선택의 노선과 모델이 발행 정보와 다르다");
            }
            final boolean all = policy.includeAll(committed.get(Key.of(current)), current);
            decisions.put(source.observationId(), all);
            if (all) {
                advances.add(current);
            }
        }
        List<SeatForecast> selected = publication.predictions()
            .stream()
            .map(prediction ->
                prediction.withEvaluationDetailRequired(policy.includesDistance(
                    decisions.getOrDefault(prediction.vehicleObservationId(), true), prediction.stopsToTarget())))
            .toList();
        return new Selection(new ForecastPublication(publication.sourceBatchId(), publication.routeVersionId(),
            publication.modelDeploymentId(), publication.demandStatisticsRevision(), publication.qualityRevision(),
            publication.observedAt(), publication.generatedAt(), publication.publishedAt(), selected), advances);
    }

    private boolean shouldSkip(
        EvaluationSamplingSource source,
        EvaluationSamplingPoint current,
        Set<Long> predicted
    ) {
        if (!predicted.contains(source.observationId()) || current.direction().equals("UNKNOWN")) {
            return true;
        }
        return !routes.isEmpty() && !routes.contains(source.sourceRouteId());
    }

    private boolean belongsToPublication(EvaluationSamplingPoint current, ForecastPublication publication) {
        return current.routeVersionId() == publication.routeVersionId()
            && current.modelDeploymentId() == publication.modelDeploymentId();
    }

    /**
     * 성공한 발행의 표본 기준만 앞으로 이동한다. DB 커밋 후에 호출하며 역순 시각으로 기준을 되돌리지 않는다.
     */
    public synchronized void committed(Selection selection) {
        for (EvaluationSamplingPoint point : selection.advances()) {
            Key key = Key.of(point);
            EvaluationSamplingPoint previous = committed.get(key);
            if (previous != null && !point.observedAt().isAfter(previous.observedAt())) {
                continue;
            }
            committed.remove(key);
            committed.put(key, point);
            while (committed.size() > MAX_VEHICLES) {
                committed.remove(committed.keySet().iterator().next());
            }
        }
    }

    public record Selection(ForecastPublication publication, List<EvaluationSamplingPoint> advances) {
        public Selection {
            advances = List.copyOf(advances);
        }
    }

    private record Key(long routeVersion, String vehicle) {
        static Key of(EvaluationSamplingPoint point) {
            return new Key(point.routeVersionId(), point.vehicleId());
        }
    }
}
