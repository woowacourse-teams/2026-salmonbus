package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.forecasting.domain.evaluation.SameDayFullOutcomeCount;
import com.gustler.backend.forecasting.domain.evaluation.SameDayFullOutcomesRepository;
import com.gustler.backend.forecasting.domain.evaluation.SeoulDay;
import com.gustler.backend.forecasting.domain.evaluation.SettledForecast;

import com.gustler.backend.forecasting.domain.model.SameDayFullOutcomes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 당일 확정된 평가를 모아 만석 확률 보정에 필요한 집계를 제공한다.
 *
 * <p>일반 조회에서는 저장된 집계를 사용한다. 집계가 없으면 평가 결과로 초기화하고,
 * 과거 수집 배치를 처리할 때는 해당 관측 시각까지 도착한 결과만 다시 센다.
 * 초기화와 새 평가의 반영은 같은 노선 잠금 안에서 처리한다.
 */
@Component
public class SameDayFullOutcomesService {

    private final SameDayFullOutcomesRepository repository;

    public SameDayFullOutcomesService(
        SameDayFullOutcomesRepository repository
    ) {
        this.repository = repository;
    }

    @Transactional
    public Map<Integer, SameDayFullOutcomes> outcomesFor(
        final long routeId,
        Instant predictionAt
    ) {
        repository.lockRoute(routeId);
        SeoulDay day = SeoulDay.containing(predictionAt);
        List<SameDayFullOutcomeCount> counts = repository.findCounts(routeId, day);
        if (counts.isEmpty()) {
            counts = initializeCounts(routeId, day);
        }
        if (predictionAt.isBefore(settledThroughOf(counts))) {
            return outcomesOf(repository.countFromSource(routeId, day, predictionAt));
        }
        return outcomesOf(counts);
    }

    @Transactional
    public void record(
        List<SettledForecast> settled
    ) {
        settled.stream().map(SettledForecast::routeId).distinct().sorted().forEach(repository::lockRoute);
        for (Map.Entry<RouteDay, List<SettledForecast>> group : groupByRouteDay(settled).entrySet()) {
            RouteDay key = group.getKey();
            if (repository.findCounts(key.routeId(), key.day()).isEmpty()) {
                initializeCounts(key.routeId(), key.day());
                continue;
            }
            for (SettledForecast forecast : group.getValue()) {
                repository.add(forecast);
            }
        }
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

    private List<SameDayFullOutcomeCount> initializeCounts(
        final long routeId,
        SeoulDay day
    ) {
        List<SameDayFullOutcomeCount> counted = repository.countFromSource(routeId, day, day.end());
        if (counted.isEmpty()) {
            // 거리 0은 실제 예보가 아니다. 이 날짜/품질 버전에서 원본이 비었음을 한 번만 기록한다.
            counted = List.of(new SameDayFullOutcomeCount(0, 0, 0, 0, day.start()));
        }
        repository.upsertCounts(routeId, day, counted);
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
        SeoulDay day
    ) {

        static RouteDay of(
            SettledForecast forecast
        ) {
            return new RouteDay(forecast.routeId(), SeoulDay.containing(forecast.arrivedAt()));
        }
    }
}
