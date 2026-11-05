package com.gustler.backend.forecasting.domain.model.les;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 여섯 필드는 한 노선의 고정 통계 계약이며, 없는 셀에는 원본이 정한 기본값만 사용한다. */
public record LesRouteStatistics(
    String route,
    List<LesRouteStatistics.Stop> stops,
    double minutesPerStop,
    Map<String, Map<String, Double>> tables,
    Map<String, List<Double>> targetBands,
    Set<LocalDate> holidays,
    boolean confirmedProfile
) {
    public static final String CONFIRMED_PROFILE = "les-confirmed-closure-v2";
    public static final String CONFIRMED_LABEL = "les-confirmed-routeband-v2";

    public LesRouteStatistics(
        String route,
        List<LesRouteStatistics.Stop> stops,
        final double minutesPerStop,
        Map<String, Map<String, Double>> tables,
        Map<String, List<Double>> targetBands,
        Set<LocalDate> holidays
    ) {
        this(route, stops, minutesPerStop, tables, targetBands, holidays, false);
    }
    /** 다섯 값은 하나의 원본 정류장 메타데이터를 구성한다. */
    public record Stop(int order, String id, String name, boolean down, boolean boardingAllowed) {
        public Stop {
            if (hasInvalidMetadata(order, id, name)) {
                throw new IllegalArgumentException("Invalid Les stop metadata");
            }
        }

        private static boolean hasInvalidMetadata(final int order, String id, String name) {
            if (order < 1 || id == null) {
                return true;
            }
            return id.isBlank() || name == null;
        }

        public boolean via() {
            return name.contains("경유");
        }
    }

    public LesRouteStatistics {
        if (route == null || route.isBlank()) {
            throw new IllegalArgumentException("Les route is absent");
        }
        stops = List.copyOf(stops);
        if (stops.stream().map(LesRouteStatistics.Stop::order).distinct().count() != stops.size()) {
            throw new IllegalArgumentException("Duplicate Les stop order");
        }
        tables = tables.entrySet().stream()
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> Map.copyOf(entry.getValue())));
        targetBands = targetBands.entrySet().stream()
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
        holidays = Set.copyOf(holidays);
        validateTravelTime(stops, minutesPerStop);
        validateTables(tables);
    }

    private static void validateTravelTime(List<LesRouteStatistics.Stop> stops, final double minutesPerStop) {
        if (stops.isEmpty() || !Double.isFinite(minutesPerStop)) {
            throw new IllegalArgumentException("Les route statistics missing stops/travel time");
        }
        if (minutesPerStop <= 0) {
            throw new IllegalArgumentException("Les route statistics missing stops/travel time");
        }
    }

    private static void validateTables(Map<String, Map<String, Double>> tables) {
        for (String table : List.of("lam", "lam_base", "net_tab", "full_tab", "dep_tab", "stop_net_base")) {
            if (!tables.containsKey(table)) {
                throw new IllegalArgumentException("Missing Les table: " + table);
            }
        }
    }

    public String dayType(LocalDate date) {
        if (date.getDayOfWeek().getValue() >= 6 || holidays.contains(date)) {
            return "off";
        }
        return "work";
    }

    public int largestStopOrder() {
        return stops.stream().mapToInt(LesRouteStatistics.Stop::order).max().orElseThrow();
    }

    public int turn() {
        return stops.stream().filter(stop -> !stop.down()).mapToInt(LesRouteStatistics.Stop::order).max().orElseThrow();
    }

    public List<Integer> realStopOrders() {
        if (confirmedProfile) {
            return stops.stream().filter(Stop::boardingAllowed).map(Stop::order).sorted().toList();
        }
        return stops.stream().filter(stop -> !stop.via()).map(LesRouteStatistics.Stop::order).sorted().toList();
    }

    public Profile profile() {
        if (confirmedProfile) {
            return new Profile(CONFIRMED_PROFILE, CONFIRMED_LABEL,
                "observed-vehicle-prefix-max-through-closure-v2");
        }
        return new Profile("les-original-v1", "les-identity-safe-v1",
            "observed-route-day-max-through-closure-v1");
    }

    public record Profile(String input, String label, String capacity) {
    }

    public LesRouteStatistics.Stop stop(final int order) {
        return stops.stream().filter(stop -> stop.order() == order).findFirst().orElseThrow();
    }

    public double cell(
        String table,
        final int stop,
        String day,
        final int minute
    ) {
        return tables.get(table).getOrDefault(stop + "|" + day + "|" + minute / 30, 0.0);
    }

    public double rate(final int stop, String day, final int minute) {
        return tables.get("lam").getOrDefault(stop + "|" + day + "|" + minute / 30,
            tables.get("lam_base").getOrDefault(stop + "|" + day, 0.0));
    }

    public double typicalNet(final int stop, String day, final int minute) {
        return tables.get("net_tab").getOrDefault(stop + "|" + day + "|" + minute / 30,
            tables.get("stop_net_base").getOrDefault(stop + "|" + day, 0.0));
    }

    public List<Double> targetMeans(final int stop, String band) {
        return targetBands.getOrDefault(stop + "|" + band, List.of(Double.NaN, Double.NaN, Double.NaN));
    }
}
