package com.gustler.backend.forecasting.domain.model.alighting;

import java.util.Comparator;
import java.util.List;

/** 모델에 전달할 순서는 경유 직후 정류장 순위, 직전 연속 경유 수, 앞뒤 경유 여부, 출발과 목표 사이의 경유 수다. */
final class AlightingAwareEntryFeatures {

    private static final int MAX_ENTRY_RANK = 3;

    private AlightingAwareEntryFeatures() {
    }

    static double[] calculate(AlightingAwareRouteStatistics statistics, final int target, final int origin) {
        List<AlightingAwareRouteStatistics.Stop> stops = statistics.stops();
        final long viaBetween = stops.stream()
            .filter(AlightingAwareRouteStatistics.Stop::via)
            .filter(stop -> origin < stop.order() && stop.order() < target)
            .count();

        return stops.stream()
            .filter(stop -> stop.order() == target && !stop.via())
            .findFirst()
            .map(stop -> entryAtStop(stops, stop, viaBetween))
            .orElseGet(() -> new double[] { 0, 0, 0, viaBetween });
    }

    private static double[] entryAtStop(
        List<AlightingAwareRouteStatistics.Stop> stops,
        AlightingAwareRouteStatistics.Stop target,
        final long viaBetween
    ) {
        List<AlightingAwareRouteStatistics.Stop> directionStops = stops.stream()
            .filter(stop -> stop.down() == target.down())
            .sorted(Comparator.comparingInt(AlightingAwareRouteStatistics.Stop::order))
            .toList();
        final int targetIndex = directionStops.indexOf(target);
        final int maxRank = Math.min(MAX_ENTRY_RANK, targetIndex);
        final boolean nextStopIsVia = targetIndex + 1 < directionStops.size()
            && directionStops.get(targetIndex + 1).via();

        for (int rank = 1; rank <= maxRank; rank++) {
            final int previousIndex = targetIndex - rank;
            if (!directionStops.get(previousIndex).via()) {
                continue;
            }
            if (rank == 1 && nextStopIsVia) {
                return new double[] { 0, 0, 1, viaBetween };
            }
            final long viaRun = directionStops.subList(0, previousIndex + 1).reversed().stream()
                .takeWhile(AlightingAwareRouteStatistics.Stop::via)
                .count();
            return new double[] { rank, viaRun, 0, viaBetween };
        }
        return new double[] { 0, 0, 0, viaBetween };
    }
}
