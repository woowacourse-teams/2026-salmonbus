package com.gustler.backend.processor;

import com.gustler.backend.processor.seatdistribution.SameDayFullOutcomes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 오늘 도착이 확인된 예보들의 성적. 만석 확률을 당일 성적으로 옮기는 데 쓴다.
 *
 * <p>정산이 예보를 닫을 때마다 한 칸씩 더해 둔 것을 읽는다. <b>batch 마다 원본을 다시 세지 않는다.</b>
 * 다시 세는 자리는 둘이다. 그 노선의 오늘 칸이 아직 없을 때와, 예보 시각이 집계에 반영된 마지막
 * 도착보다 앞일 때다. 뒤쪽은 장애로 밀린 batch 를 뒤늦게 처리하는 경우인데, 그때 집계를 그대로 쓰면
 * 그 batch 가 아직 모르는 도착까지 센 값을 보게 된다.
 */
@Component
public class SameDayFullOutcomesService {

    private final SameDayFullOutcomesRepository repository;

    public SameDayFullOutcomesService(
        SameDayFullOutcomesRepository repository
    ) {
        this.repository = repository;
    }

    public Map<Integer, SameDayFullOutcomes> outcomesFor(
        final long routeId,
        Instant predictionAt
    ) {
        SeoulDay day = SeoulDay.containing(predictionAt);
        List<SameDayFullOutcomeCount> counts = repository.findCounts(routeId, day);
        if (counts.isEmpty()) {
            counts = seed(routeId, day);
        }
        if (predictionAt.isBefore(settledThroughOf(counts))) {
            return outcomesOf(repository.countFromSource(routeId, day, predictionAt));
        }
        return outcomesOf(counts);
    }

    public void record(
        List<SettledForecast> settled
    ) {
        for (Map.Entry<RouteDay, List<SettledForecast>> group : groupByRouteDay(settled).entrySet()) {
            RouteDay key = group.getKey();
            if (repository.findCounts(key.routeId(), key.day()).isEmpty()) {
                seed(key.routeId(), key.day());
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

    private List<SameDayFullOutcomeCount> seed(
        final long routeId,
        SeoulDay day
    ) {
        List<SameDayFullOutcomeCount> counted = repository.countFromSource(routeId, day, day.end());
        if (counted.isEmpty()) {
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
