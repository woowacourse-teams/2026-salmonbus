package com.gustler.backend.forecasting.infrastructure.alighting;

import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareFeatureSnapshot;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareFeatureVector;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareHistoryCompactor;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareObservation;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwarePassEvent;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareVehiclePrefix;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 원자료 계산을 비교하며, 실시간 확정 시점 차이는 원본 수치 동등성 검증과 따로 보고한다. */
public final class AlightingAwareSourceFeatureCheck {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("fixture and new output paths required");
        }
        if (Files.isDirectory(Path.of(args[0]))) {
            runDirectory(Path.of(args[0]), Path.of(args[1]));
            return;
        }
        Path output = Path.of(args[1]);
        if (Files.exists(output)) {
            throw new IllegalArgumentException("Output already exists");
        }
        JsonMapper json = JsonMapper.builder().build();
        JsonNode root = json.readTree(Files.readAllBytes(Path.of(args[0])));
        Fixture fixture = fixture(root);
        Comparison comparison = new Comparison();
        for (JsonNode row : root.path("cases")) {
            comparison.accept(row, fixture);
        }
        Files.writeString(output,
            json.writerWithDefaultPrettyPrinter().writeValueAsString(comparison.report(root, fixture)));
        comparison.reportOutcome(output);
    }

    private static void runDirectory(Path source, Path output) throws Exception {
        Files.createDirectory(output);
        List<Path> failures = new ArrayList<>();
        try (Stream<Path> entries = Files.list(source)) {
            List<Path> fixtures = entries.filter(path -> path.toString().endsWith(".json"))
                .filter(path -> !path.getFileName().toString().equals("manifest.json")).sorted().toList();
            for (Path fixture : fixtures) {
                try {
                    main(new String[] { fixture.toString(), output.resolve(fixture.getFileName()).toString() });
                } catch (IllegalStateException failure) {
                    failures.add(fixture);
                }
            }
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException("Source parity failed for " + failures);
        }
    }

    private static Fixture fixture(JsonNode root) {
        Set<LocalDate> holidays = new HashSet<>();
        for (JsonNode day : root.path("holidays")) {
            holidays.add(LocalDate.parse(day.asText()));
        }
        AlightingAwareRouteStatistics statistics = AlightingAwareStatisticsReader.read(root.path("statistics"), holidays);
        List<AlightingAwareObservation> observations = observations(root.path("observations"));
        Map<Long, AlightingAwareObservation> byId = new LinkedHashMap<>();
        for (AlightingAwareObservation observation : observations) {
            byId.put(observation.observationId(), observation);
        }
        List<AlightingAwarePassEvent> research = researchEvents(root.path("research_events"));
        Map<String, AlightingAwareVehiclePrefix> prefix = prefixes(root.path("prefix"));
        List<AlightingAwarePassEvent> causal = AlightingAwareHistoryCompactor.resolve(
            new AlightingAwareHistoryCompactor.History(prefix, observations, List.of()), statistics.realStopOrders());
        return new Fixture(statistics, observations, byId, research, prefix, causal);
    }

    private static List<AlightingAwareObservation> observations(JsonNode rows) {
        List<AlightingAwareObservation> observations = new ArrayList<>();
        for (JsonNode row : rows) {
            observations.add(observation(row));
        }
        return observations;
    }

    private static AlightingAwareObservation observation(JsonNode row) {
        if (row.has("route_version_id")) {
            return new AlightingAwareObservation(row.path("id").asLong(), row.path("batch_id").asLong(),
                row.path("batch_rank").asLong(), Instant.parse(row.path("received_at").asText()),
                row.path("vehicle_id").asText(), row.path("passed_stop_order").asInt(),
                row.path("stop_order").asInt(), row.path("running_state").asInt(),
                nullableSeats(row.path("remaining_seats")), row.path("crowd_level").asInt(),
                row.path("route_version_id").asLong(), Instant.parse(row.path("physical_received_at").asText()),
                row.path("down").asBoolean());
        }
        return new AlightingAwareObservation(row.path("id").asLong(), row.path("batch_id").asLong(),
            row.path("batch_rank").asLong(),
            Instant.parse(row.path("received_at").asText()), row.path("vehicle_id").asText(),
            row.path("passed_stop_order").asInt(), row.path("stop_order").asInt(), row.path("running_state").asInt(),
            nullableSeats(row.path("remaining_seats")), row.path("crowd_level").asInt());
    }

    private static Integer nullableSeats(JsonNode value) {
        if (value.isNull()) {
            return null;
        }
        return value.asInt();
    }

    private static List<AlightingAwarePassEvent> researchEvents(JsonNode rows) {
        List<AlightingAwarePassEvent> research = new ArrayList<>();
        long identity = -1;
        for (JsonNode row : rows) {
            String vehicle = row.path("vehicle_id").asText();
            final int stop = row.path("stop_order").asInt();
            AlightingAwareObservation arrival = new AlightingAwareObservation(identity--, 0, 0,
                Instant.parse(row.path("arrive_at").asText()), vehicle, stop - 1, stop, 1, row.path("arrive").asInt(),
                0);
            AlightingAwareObservation departure = new AlightingAwareObservation(identity--, 0, 0,
                Instant.parse(row.path("known_at").asText()), vehicle, stop, stop, 2, row.path("depart").asInt(), 0);
            research.add(new AlightingAwarePassEvent(vehicle, row.path("trip_id").asLong(), stop, arrival, departure,
                departure.receivedAt()));
        }
        return research;
    }

    private static Map<String, AlightingAwareVehiclePrefix> prefixes(JsonNode states) {
        Map<String, AlightingAwareVehiclePrefix> prefix = new LinkedHashMap<>();
        for (JsonNode state : states) {
            AlightingAwareObservation previous = observation(state.path("previous"));
            prefix.put(previous.vehicleId(), new AlightingAwareVehiclePrefix((float) state.path("capacity").asDouble(),
                state.path("full_streak").asDouble(), previous, Instant.parse(state.path("entry_at").asText()),
                state.path("trip_id").asLong()));
        }
        return prefix;
    }

    private static double compare(
        List<Map<String, Object>> differences,
        final int row,
        String vector,
        JsonNode expected,
        double[] actual
    ) {
        double maximum = 0;
        for (int index = 0; index < actual.length; index++) {
            final double expectedValue = expectedNumber(expected.get(index));
            final double actualValue = actual[index];
            if (Double.doubleToLongBits(expectedValue) == Double.doubleToLongBits(actualValue)) {
                continue;
            }
            final double difference = Math.abs(expectedValue - actualValue);
            maximum = Math.max(maximum, reportableDifference(difference));
            differences.add(Map.of("row", row, "vector", vector, "column", index,
                "expected", reportableNumber(expectedValue), "actual", reportableNumber(actualValue)));
        }
        return maximum;
    }

    private static double expectedNumber(JsonNode value) {
        if (value.isNull()) {
            return Double.NaN;
        }
        return value.asDouble();
    }

    private static double reportableDifference(final double difference) {
        if (Double.isFinite(difference)) {
            return difference;
        }
        return Double.POSITIVE_INFINITY;
    }

    private static Object reportableNumber(final double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        return value;
    }

    /** 여섯 필드는 연구 입력과 운영 입력을 같은 원자료로 비교하는 하나의 fixture 계약이다. */
    private record Fixture(
        AlightingAwareRouteStatistics statistics,
        List<AlightingAwareObservation> observations,
        Map<Long, AlightingAwareObservation> byId,
        List<AlightingAwarePassEvent> research,
        Map<String, AlightingAwareVehiclePrefix> prefix,
        List<AlightingAwarePassEvent> causal
    ) {
        private AlightingAwareFeatureVector vector(JsonNode row, List<AlightingAwarePassEvent> events) {
            AlightingAwareObservation source = byId.get(row.path("source_id").asLong());
            final int target = row.path("target").asInt();
            return new AlightingAwareFeatureSnapshot(
            Math.max(source.routeVersionId(), 1), source, row.path("seat88").get(53).asInt(), statistics, observations, events, prefix).forTarget(target);
        }
    }

    private static final class Comparison {
        private final List<Map<String, Object>> differences = new ArrayList<>();
        private final List<Map<String, Object>> causalDifferences = new ArrayList<>();
        private int cases;
        private double maximum;

        private void accept(JsonNode row, Fixture fixture) {
            List<AlightingAwarePassEvent> reference = fixture.research();
            if (fixture.statistics().confirmedProfile()) {
                reference = fixture.causal();
            }
            AlightingAwareFeatureVector actual = fixture.vector(row, reference);
            AlightingAwareFeatureVector causal = fixture.vector(row, fixture.causal());
            maximum = Math.max(maximum, compare(differences, cases, "seat88", row.path("seat88"), actual.seat88()));
            maximum = Math.max(maximum,
                compare(differences, cases, "probability80", row.path("probability80"), actual.probability80()));
            compare(causalDifferences, cases, "seat88", row.path("seat88"), causal.seat88());
            compare(causalDifferences, cases, "probability80", row.path("probability80"), causal.probability80());
            cases++;
        }

        private Map<String, Object> report(JsonNode root, Fixture fixture) {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("route", root.path("route").asText());
            report.put("day", root.path("day").asText());
            report.put("cases", cases);
            report.put("source_observations", fixture.observations().size());
            report.put("research_max_absolute_difference", maximum);
            report.put("research_differences", differences);
            report.put("causal_differences", causalDifferences);
            report.put("research_parity_passed", differences.isEmpty() && cases > 0);
            report.put("serving_ready", false);
            return report;
        }

        private void reportOutcome(Path output) {
            System.out.println("cases=" + cases + " research differences=" + differences.size()
                + " causal differences=" + causalDifferences.size());
            if (!differences.isEmpty() || cases == 0) {
                throw new IllegalStateException("Real source feature parity failed: " + output);
            }
        }
    }
}
