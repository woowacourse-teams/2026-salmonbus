package com.gustler.backend.forecasting.infrastructure.alighting;

import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

public final class AlightingAwareStatisticsReader {

    private static final List<String> REQUIRED_TABLES = List.of(
            "lam",
            "lam_base",
            "net_tab",
            "full_tab",
            "dep_tab",
            "stop_net_base"
    );

    private AlightingAwareStatisticsReader() {
    }

    public static AlightingAwareRouteStatistics read(JsonNode json, Set<LocalDate> holidays) {
        List<AlightingAwareRouteStatistics.Stop> stops = stops(json.path("route_metadata"));
        JsonNode tables = json.path("tables");
        Map<String, Map<String, Double>> valuesByTable = readRequiredTables(tables);
        Map<String, List<Double>> meansByTargetBand = targetBands(tables.path("target_band"));
        return new AlightingAwareRouteStatistics(
                json.path("route").asText(),
                stops,
                json.path("min_per_stop").asDouble(Double.NaN),
                valuesByTable,
                meansByTargetBand,
                holidays,
                confirmedProfile(json)
        );
    }

    private static boolean confirmedProfile(JsonNode json) {
        String profile = json.path("input_profile").asText("alighting-original-v1");
        if (profile.equals(AlightingAwareRouteStatistics.CONFIRMED_PROFILE)) {
            return true;
        }
        if (!profile.equals("alighting-original-v1")) {
            throw new IllegalArgumentException("지원하지 않는 예보 입력 프로필이다");
        }
        return false;
    }

    private static List<AlightingAwareRouteStatistics.Stop> stops(JsonNode rows) {
        return rows.valueStream()
            .map(row -> new AlightingAwareRouteStatistics.Stop(
                    row.path("order").asInt(),
                    row.path("id").asText(),
                    row.path("name").asText(),
                    row.path("direction").asText().equals("DOWN"),
                    row.path("boardingAllowed").asBoolean()
            ))
            .toList();
    }

    private static Map<String, Map<String, Double>> readRequiredTables(JsonNode root) {
        Map<String, Map<String, Double>> tables = new HashMap<>();
        for (String name : REQUIRED_TABLES) {
            if (!root.has(name)) {
                throw new IllegalArgumentException("Missing AlightingAware table " + name);
            }
            tables.put(name, table(root.path(name)));
        }
        return tables;
    }

    private static Map<String, Double> table(JsonNode rows) {
        Map<String, Double> values = new HashMap<>();
        for (JsonNode row : rows) {
            String cellKey = row.path("key").valueStream()
                .map(JsonNode::asText)
                .collect(Collectors.joining("|"));
            final double value = row.path("value").asDouble(Double.NaN);
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Nonfinite AlightingAware table value");
            }
            if (values.put(cellKey, value) != null) {
                throw new IllegalArgumentException("Duplicate AlightingAware cell");
            }
        }
        return values;
    }

    private static Map<String, List<Double>> targetBands(JsonNode rows) {
        Map<String, List<Double>> bands = new HashMap<>();
        for (JsonNode row : rows) {
            List<Double> means = row.path("values").valueStream()
                .map(AlightingAwareStatisticsReader::targetMean)
                .toList();
            if (means.size() != 3) {
                throw new IllegalArgumentException("AlightingAware target means require three columns");
            }
            String targetBand = row.path("stop").asInt() + "|" + row.path("band").asText();
            bands.put(targetBand, means);
        }
        return bands;
    }

    private static double targetMean(JsonNode value) {
        if (value.isNull()) {
            return Double.NaN;
        }
        return value.asDouble();
    }
}
