package com.gustler.backend.forecasting.domain.model.les;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 경유 구간 직후 정류장의 위치를 원본 stop_features 순서대로 계산한다. */
final class LesEntryFeatures {
    private LesEntryFeatures() {
    }

    static double[] calculate(LesRouteStatistics statistics, final int target, final int origin) {
        Map<Integer, double[]> entries = new HashMap<>();
        for (final boolean down : List.of(false, true)) {
            List<LesRouteStatistics.Stop> stops = statistics.stops().stream()
                .filter(stop -> stop.down() == down)
                .sorted(Comparator.comparingInt(LesRouteStatistics.Stop::order))
                .toList();
            collect(stops, entries);
        }
        double[] entry = entries.getOrDefault(target, new double[3]);
        final long via = statistics.stops().stream()
            .filter(stop -> isViaBetween(stop, origin, target))
            .count();
        return new double[] { entry[0], entry[1], entry[2], via };
    }

    private static boolean isViaBetween(
        LesRouteStatistics.Stop stop,
        final int origin,
        final int target) {
        if (!stop.via()) {
            return false;
        }
        return stop.order() > origin && stop.order() < target;
    }

    private static void collect(List<LesRouteStatistics.Stop> stops, Map<Integer, double[]> entries) {
        EntryRun run = new EntryRun();
        for (int index = 0; index < stops.size(); index++) {
            LesRouteStatistics.Stop stop = stops.get(index);
            if (stop.via()) {
                run.addVia();
                entries.put(stop.order(), new double[3]);
                continue;
            }
            final boolean afterVia = index + 1 < stops.size() && stops.get(index + 1).via();
            entries.put(stop.order(), run.finishStop(afterVia));
        }
    }

    private static final class EntryRun {
        private int rank;
        private int runBefore;
        private int currentRun;

        private void addVia() {
            currentRun++;
        }

        private double[] finishStop(final boolean afterVia) {
            advanceRank();
            final boolean island = currentRun > 0 && afterVia;
            double[] values = entryValues(island);
            if (island) {
                rank = 0;
            }
            currentRun = 0;
            return values;
        }

        private void advanceRank() {
            if (currentRun > 0) {
                rank = 1;
                runBefore = currentRun;
                return;
            }
            if (rank <= 0) {
                return;
            }
            if (rank < 3) {
                rank++;
                return;
            }
            rank = 0;
        }

        private double[] entryValues(final boolean island) {
            if (island) {
                return new double[] { 0, 0, 1 };
            }
            if (rank > 0) {
                return new double[] { rank, runBefore, 0 };
            }
            return new double[] { rank, 0, 0 };
        }
    }
}
