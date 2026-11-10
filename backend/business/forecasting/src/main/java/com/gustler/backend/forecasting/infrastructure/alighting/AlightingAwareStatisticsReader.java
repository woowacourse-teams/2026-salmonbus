package com.gustler.backend.forecasting.infrastructure.alighting;

import static com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareIdentifiers.canonical;

import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

public final class AlightingAwareStatisticsReader {
    private AlightingAwareStatisticsReader() {
    }

    public static AlightingAwareRouteStatistics read(JsonNode json, Set<LocalDate> holidays) {
        List<AlightingAwareRouteStatistics.Stop> stops = stops(json.path("route_metadata"));
        Map<String, Map<String, Double>> tables = tables(json.path("tables"));
        Map<String, List<Double>> bands = bands(json.path("tables").path("target_band"));
        return new AlightingAwareRouteStatistics(json.path("route").asText(), stops,
            json.path("min_per_stop").asDouble(Double.NaN), tables, bands, holidays, confirmedProfile(json));
    }

    private static boolean confirmedProfile(JsonNode json) {
        String profile = canonical(json.path("input_profile").asText("alighting-original-v1"));
        if (profile.equals(AlightingAwareRouteStatistics.CONFIRMED_PROFILE)) {
            return true;
        }
        if (!profile.equals("alighting-original-v1")) {
            throw new IllegalArgumentException("Unknown AlightingAware input profile");
        }
        return false;
    }

    private static List<AlightingAwareRouteStatistics.Stop> stops(JsonNode rows) {
        List<AlightingAwareRouteStatistics.Stop> stops = new ArrayList<>();
        for (JsonNode row : rows) {
            stops.add(new AlightingAwareRouteStatistics.Stop(row.path("order").asInt(), row.path("id").asText(),
                row.path("name").asText(), row.path("direction").asText().equals("DOWN"),
                row.path("boardingAllowed").asBoolean()));
        }
        return stops;
    }

    private static Map<String, Map<String, Double>> tables(JsonNode root) {
        Map<String, Map<String, Double>> tables = new HashMap<>();
        for (String name : List.of("lam", "lam_base", "net_tab", "full_tab", "dep_tab", "stop_net_base")) {
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
            List<String> key = new ArrayList<>();
            for (JsonNode part : row.path("key")) {
                key.add(part.asText());
            }
            final double value = row.path("value").asDouble(Double.NaN);
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Nonfinite AlightingAware table value");
            }
            if (values.put(String.join("|", key), value) != null) {
                throw new IllegalArgumentException("Duplicate AlightingAware cell");
            }
        }
        return values;
    }

    private static Map<String, List<Double>> bands(JsonNode rows) {
        Map<String, List<Double>> bands = new HashMap<>();
        for (JsonNode row : rows) {
            List<Double> values = new ArrayList<>();
            for (JsonNode value : row.path("values")) {
                values.add(targetMean(value));
            }
            if (values.size() != 3) {
                throw new IllegalArgumentException("AlightingAware target means require three columns");
            }
            bands.put(row.path("stop").asInt() + "|" + row.path("band").asText(), values);
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
