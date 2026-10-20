package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.diagnostics.WorkerOperationLog;
import com.gustler.backend.forecasting.domain.evaluation.SameDayFullOutcomeCount;
import com.gustler.backend.forecasting.domain.evaluation.SameDayFullOutcomesStore;
import com.gustler.backend.forecasting.domain.evaluation.SeoulDay;
import com.gustler.backend.forecasting.domain.evaluation.SettledForecast;
import com.gustler.backend.forecasting.domain.model.SameDayFullOutcomes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 저장된 당일 성적만 예보 보정에 사용한다. 미초기화/과거 시점 예보는 당일 보정을 생략한다.
 * 원본 계산은 별도 초기화 트랜잭션에서만 수행하며, 준비 전 정산도 원본에는 남는다.
 */
@Component
public class SameDayFullOutcomesService {
    private final SameDayFullOutcomesStore repository;

    public SameDayFullOutcomesService(
        SameDayFullOutcomesStore repository
    ) {
        this.repository = repository;
    }

    public Map<Integer, SameDayFullOutcomes> outcomesFor(
        final long routeId,
        long modelDeploymentId,
        Instant predictionAt
    ) {
        SeoulDay day = SeoulDay.containing(predictionAt);
        List<SameDayFullOutcomeCount> counts = WorkerOperationLog.measure("same_day_read", routeId,
            () -> repository.findCounts(routeId, modelDeploymentId, day));
        if (counts.isEmpty() || predictionAt.isBefore(settledThroughOf(counts))) {
            return Map.of();
        }
        return outcomesOf(counts);
    }

    public void record(
        List<SettledForecast> settled
    ) {
        for (Map.Entry<RouteDay, List<SettledForecast>> group : groupByRouteDay(settled).entrySet()) {
            RouteDay key = group.getKey();
            if (repository.findCounts(key.routeId(), key.modelDeploymentId(), key.day()).isEmpty()) {
                // 부분 합계를 만들면 초기화 완료로 오인한다. 원본은 이후 초기화에서 함께 센다.
                continue;
            }
            repository.addCounts(key.routeId(), key.modelDeploymentId(), key.day(), incrementsOf(group.getValue()));
        }
    }

    private static List<SameDayFullOutcomeCount> incrementsOf(List<SettledForecast> settled) {
        Map<Integer, SameDayFullOutcomeCount> increments = new LinkedHashMap<>();
        for (SettledForecast forecast : settled) {
            SameDayFullOutcomeCount next = new SameDayFullOutcomeCount(
                forecast.stopsToTarget(), 1, forecast.wasFull() ? 1 : 0,
                forecast.rawFullChance(), forecast.arrivedAt());
            increments.merge(forecast.stopsToTarget(), next, (previous, current) ->
                new SameDayFullOutcomeCount(previous.stopsToTarget(),
                    previous.rowCount() + current.rowCount(),
                    previous.actualFullCount() + current.actualFullCount(),
                    previous.rawFullChanceSum() + current.rawFullChanceSum(),
                    previous.settledThrough().isAfter(current.settledThrough())
                        ? previous.settledThrough() : current.settledThrough()));
        }
        return List.copyOf(increments.values());
    }

    private static Map<RouteDay, List<SettledForecast>> groupByRouteDay(
        List<SettledForecast> settled
    ) {
        Map<RouteDay, List<SettledForecast>> byRouteDay = new LinkedHashMap<>();
        for (SettledForecast forecast : settled) {
            byRouteDay.computeIfAbsent(RouteDay.of(forecast), key -> new ArrayList<>()).add(forecast);
        }
        return byRouteDay;
    }

    /** 호출자가 같은 노선 잠금을 유지하는 트랜잭션 안에서 사용한다. */
    public boolean initializeIfAbsent(long routeId, long modelDeploymentId, SeoulDay day) {
        return initializeIfAbsent(routeId, modelDeploymentId, day, new SameDayInitializationAttempt());
    }

    boolean initializeIfAbsent(long routeId, long modelDeploymentId,
        SeoulDay day,
        SameDayInitializationAttempt attempt
    ) {
        if (!attempt.measure(SameDayInitializationAttempt.Stage.CHECK,
            () -> repository.findCounts(routeId, modelDeploymentId, day)).isEmpty()) {
            return false;
        }
        seed(routeId, modelDeploymentId, day, attempt);
        return true;
    }

    private List<SameDayFullOutcomeCount> seed(
        final long routeId,
        long modelDeploymentId,
        SeoulDay day,
        SameDayInitializationAttempt attempt
    ) {
        List<SameDayFullOutcomeCount> counted = attempt.measure(SameDayInitializationAttempt.Stage.SOURCE,
            () -> repository.countFromSource(routeId, modelDeploymentId, day, day.end()));
        if (counted.isEmpty()) {
            // 거리 0은 실제 예보가 아니다. 이 날짜/품질 버전에서 원본이 비었음을 한 번만 기록한다.
            counted = List.of(new SameDayFullOutcomeCount(0, 0, 0, 0, day.start()));
        }
        List<SameDayFullOutcomeCount> toSave = counted;
        attempt.run(SameDayInitializationAttempt.Stage.SAVE, () -> repository.upsertCounts(routeId, modelDeploymentId, day, toSave));
        return counted;
    }

    private static Instant settledThroughOf(
        List<SameDayFullOutcomeCount> counts
    ) {
        Instant latest = Instant.MIN;
        for (SameDayFullOutcomeCount count : counts) {
            if (count.settledThrough().isAfter(latest)) {
                latest = count.settledThrough();
            }
        }
        return latest;
    }

    private static Map<Integer, SameDayFullOutcomes> outcomesOf(
        List<SameDayFullOutcomeCount> counts
    ) {
        Map<Integer, SameDayFullOutcomes> byStopsAhead = new LinkedHashMap<>();
        for (SameDayFullOutcomeCount count : counts) {
            if (count.rowCount() > 0) { byStopsAhead.put(count.stopsToTarget(), count.outcomes()); }
        }
        return Map.copyOf(byStopsAhead);
    }

    private record RouteDay(
        long routeId,
        long modelDeploymentId,
        SeoulDay day
    ) {
        static RouteDay of(
            SettledForecast forecast
        ) {
            return new RouteDay(forecast.routeId(), forecast.modelDeploymentId(),
                SeoulDay.containing(forecast.arrivedAt()));
        }
    }
}
