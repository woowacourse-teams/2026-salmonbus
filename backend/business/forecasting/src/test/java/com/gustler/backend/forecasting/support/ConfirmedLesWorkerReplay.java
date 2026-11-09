package com.gustler.backend.forecasting.support;

import com.gustler.backend.forecasting.application.deployment.ActiveForecastRuntimeResolver;
import com.gustler.backend.forecasting.application.deployment.BundleActivation;
import com.gustler.backend.forecasting.application.deployment.LoadedModelRegistry;
import com.gustler.backend.forecasting.application.evaluation.SameDayFullOutcomesService;
import com.gustler.backend.forecasting.application.publication.ForecastBatchCommitter;
import com.gustler.backend.forecasting.application.publication.ForecastBatchWriter;
import com.gustler.backend.forecasting.application.RouteWorkAccess;
import com.gustler.backend.forecasting.domain.deployment.ModelDeploymentRepository;
import com.gustler.backend.forecasting.domain.deployment.ModelRelease;
import com.gustler.backend.forecasting.domain.deployment.RuntimeSnapshot;
import com.gustler.backend.forecasting.domain.deployment.SupportedForecastScope;
import com.gustler.backend.forecasting.domain.model.RouteDirection;
import com.gustler.backend.forecasting.domain.model.RouteStop;
import com.gustler.backend.forecasting.domain.model.RouteStops;
import com.gustler.backend.forecasting.domain.model.les.LesFeatureSnapshot;
import com.gustler.backend.forecasting.domain.model.les.LesFeatureVector;
import com.gustler.backend.forecasting.domain.model.les.LesForecastModel;
import com.gustler.backend.forecasting.domain.model.les.LesObservation;
import com.gustler.backend.forecasting.domain.model.les.LesRouteStatistics;
import com.gustler.backend.forecasting.domain.model.les.LesSeatPredictor;
import com.gustler.backend.forecasting.domain.model.les.LesVehiclePrefix;
import com.gustler.backend.forecasting.domain.publication.PendingForecastBatch;
import com.gustler.backend.forecasting.domain.publication.VehicleTrajectoryQuery;
import com.gustler.backend.forecasting.domain.statistics.StopDemandStatisticsRepository;
import com.gustler.backend.forecasting.infrastructure.bundle.les.LesInferenceBundle;
import com.gustler.backend.forecasting.infrastructure.bundle.les.LesServingBundle;
import com.gustler.backend.forecasting.infrastructure.jdbc.JdbcLesForecastEvaluation;
import com.gustler.backend.forecasting.infrastructure.les.ConfirmedLesInput;
import com.gustler.backend.forecasting.infrastructure.les.JdbcLesFeatureQuery;
import com.gustler.backend.forecasting.infrastructure.les.LesHistorySeedReader;
import com.gustler.backend.support.ConfirmedTripFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 실제 관측과 명시적인 자정 체크포인트를 로컬 PostgreSQL의 Worker 코드로 재생한다. 운영 연결은 하지 않는다. */
public final class ConfirmedLesWorkerReplay {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public static void main(String[] args) throws Exception {
        if ((args.length < 3 || args.length > 4) || Files.exists(Path.of(args[2]))) {
            throw new IllegalArgumentException("fixtures, inactive bundle, new output directory required");
        }
        Path output = Path.of(args[2]);
        Files.createDirectory(output);
        LesInferenceBundle bundle = LesInferenceBundle.load(Path.of(args[1]));
        LesForecastModel model = LesServingBundle.readModel(bundle);
        SpringApplication application = new SpringApplication(ForecastingTestConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setDefaultProperties(Map.of("forecast.enabled", "true", "forecast.staleness", "5m",
            "forecast.batch-limit", "20", "forecast.pending-limit", "3000", "forecast.arrival-limit", "400",
            "spring.jpa.hibernate.ddl-auto", "validate", "spring.datasource.hikari.maximum-pool-size", "3",
            "spring.datasource.hikari.minimum-idle", "1"));
        List<Map<String, Object>> results = new ArrayList<>();
        try (ConfigurableApplicationContext context = application.run()) {
            JdbcClient jdbc = context.getBean(JdbcClient.class);
            PlatformTransactionManager transactions = context.getBean(PlatformTransactionManager.class);
            TransactionTemplate tx = new TransactionTemplate(transactions);
            tx.setTimeout(90);
            ModelDeploymentRepository deployments = context.getBean(ModelDeploymentRepository.class);
            LoadedModelRegistry registry = new LoadedModelRegistry();
            BundleActivation activation = new BundleActivation(deployments, registry);
            ModelRelease release = ModelRelease.les("local-confirmed-replay", bundle.digest(),
                "2026-10-22T00:00:00+09:00", new SupportedForecastScope(bundle.routes()), model);
            tx.executeWithoutResult(status -> activation.activate(() -> release));
            RuntimeSnapshot runtime = new ActiveForecastRuntimeResolver(deployments, registry)
                .resolveActive().orElseThrow();
            JsonNode manifest = JSON.readTree(Files.readAllBytes(Path.of(args[0]).resolve("manifest.json")));
            for (JsonNode route : manifest.path("routes")) {
                if (args.length == 4 && !route.path("route").asText().equals(args[3])) {
                    continue;
                }
                byte[] bytes = Files.readAllBytes(Path.of(args[0]).resolve(route.path("file").asText()));
                if (!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
                    .equals(route.path("sha256").asText())) {
                    throw new IllegalArgumentException("Replay fixture digest changed");
                }
                JsonNode fixture = JSON.readTree(bytes);
                Map<String, Object> result = replay(context, fixture, output, bundle, model, runtime, tx);
                results.add(result);
                Files.writeString(output.resolve(fixture.path("route").asText() + ".json"),
                    JSON.writeValueAsString(result));
                System.out.println(JSON.writeValueAsString(result));
            }
        }
        int expectedRoutes = bundle.routes().size();
        if (args.length == 4) {
            expectedRoutes = 1;
        }
        final boolean passed = results.size() == expectedRoutes
            && results.stream().allMatch(result -> Boolean.TRUE.equals(result.get("passed")));
        Files.writeString(output.resolve("report.json"),
            JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "passed", passed, "routes", results, "bundle_manifest_sha256", bundle.digest(), "serving_ready", false,
                "settlement_scope", "after_alighting_only",
                "quality_scope",
                "Only chosen source observations have test-confirmed identity; production quality state unavailable; unassigned rows retain the normal eligible behavior",
                "input_scope",
                "Complete day observations and source-derived midnight prefixes; not full DB bootstrap")));
        if (!passed) {
            throw new IllegalStateException("Worker replay mismatch");
        }
    }

    private static Map<String, Object> replay(
        ConfigurableApplicationContext context,
        JsonNode fixture,
        Path output,
        LesInferenceBundle bundle,
        LesForecastModel model,
        RuntimeSnapshot runtime,
        TransactionTemplate tx
    ) throws Exception {
        JdbcClient jdbc = context.getBean(JdbcClient.class);
        RouteWorkAccess quality = context.getBean(RouteWorkAccess.class);
        final long routeId = load(context.getBean(DataSource.class), jdbc, fixture);
        Map<Long, LesObservation> byId = observations(fixture);
        Map<String, LesVehiclePrefix> prefix = prefixes(fixture);
        List<JsonNode> cases = new ArrayList<>();
        fixture.path("cases").forEach(cases::add);
        cases.sort(Comparator.comparing(row -> byId.get(row.path("source_id").asLong()).receivedAt()));
        Path seedRoot = output.resolve("checkpoints-" + fixture.path("route").asText());
        String directory = seedRoot.toString();
        Map<Long, RouteStops> catalogs = catalogs(fixture);
        for (RouteStops stops : catalogs.values()) {
            LesHistorySeedReader reader = new LesHistorySeedReader(
                seedRoot.resolve(LesRouteStatistics.CONFIRMED_PROFILE).toString(), 250_000);
            JsonNode anchor = fixture.path("anchor");
            reader.write(stops.routeVersionId(), new LesHistorySeedReader.Seed(List.of(), anchor.path("batch").asLong(),
                anchor.path("rank").asLong(), Instant.parse(anchor.path("time").asText()), prefix, List.of()));
        }
        for (JsonNode row : cases) {
            tx.executeWithoutResult(status -> ConfirmedTripFixture.include(jdbc, row.path("source_id").asLong()));
        }
        double maximumFeature = 0;
        double maximumPrediction = 0;
        for (JsonNode row : cases) {
            LesObservation source = byId.get(row.path("source_id").asLong());
            RouteStops stops = catalogs.get(source.routeVersionId());
            LesRouteStatistics statistics = model.statistics(stops);
            JdbcLesFeatureQuery query = new JdbcLesFeatureQuery(jdbc, "", 250_000, true, directory);
            LesFeatureSnapshot snapshot = query.read(source.batchId(), stops, statistics).get(source.observationId());
            if (snapshot == null) {
                throw new IllegalStateException("Missing real source " + source.observationId());
            }
            LesFeatureVector actual = snapshot.forTarget(row.path("target").asInt());
            maximumFeature = Math.max(maximumFeature, compare(row.path("seat88"), actual.seat88()));
            maximumFeature = Math.max(maximumFeature, compare(row.path("probability80"), actual.probability80()));
            LesSeatPredictor.Prediction expected = bundle.predictor().predict(vector(row.path("seat88")),
                vector(row.path("probability80")));
            ForecastBatchWriter writer = new ForecastBatchWriter(context.getBean(VehicleTrajectoryQuery.class),
                context.getBean(ForecastBatchCommitter.class), context.getBean(SameDayFullOutcomesService.class),
                context.getBean(StopDemandStatisticsRepository.class), Clock.fixed(source.receivedAt(), ZoneOffset.UTC),
                quality);
            writer.setLesFeatures(query);
            writer.setConfirmedInput(context.getBean(ConfirmedLesInput.class));
            PendingForecastBatch batch = new PendingForecastBatch(source.batchId(), source.routeVersionId(), routeId,
                source.sourceReceivedAt());
            writer.writeForecastsOf(batch, stops, runtime);
            final double prediction = jdbc
                .sql("SELECT expected_seats FROM seat_forecast WHERE vehicle_observation_id=? AND target_stop_order=?")
                .params(source.observationId(), row.path("target").asInt()).query(Double.class).single();
            maximumPrediction = Math.max(maximumPrediction, Math.abs(prediction - expected.afterAlightingSeats()));
            final double fullProbability = jdbc
                .sql("SELECT seat_full_chance FROM seat_forecast WHERE vehicle_observation_id=? AND target_stop_order=?")
                .params(source.observationId(), row.path("target").asInt()).query(Double.class).single();
            maximumPrediction = Math.max(maximumPrediction,
                Math.abs(fullProbability - expected.departureFullProbability()));
            // A restart must use the saved cursor and preserve the first confirmation.
            writer.writeForecastsOf(batch, stops, runtime);
            Instant confirmed = jdbc.sql("SELECT input_confirmed_at FROM observation_batch WHERE id=?")
                .param(source.batchId()).query(OffsetDateTime.class).single().toInstant();
            if (!confirmed.equals(source.receivedAt())) {
                throw new IllegalStateException("Confirmation moved during republish");
            }
        }
        JdbcLesForecastEvaluation evaluation = new JdbcLesForecastEvaluation(jdbc, quality,
            Clock.fixed(Instant.parse(fixture.path("day").asText() + "T23:59:59+09:00"), ZoneOffset.UTC),
            context.getBean(PlatformTransactionManager.class), 50_000, 150_000);
        final int initialWork = jdbc.sql("SELECT count(*) FROM les_forecast_evaluation_work")
            .query(Integer.class).single();
        final int maximumTicks = (initialWork + 99) / 100 + 2;
        for (int tick = 0; tick < maximumTicks; tick++) {
            evaluation.settle();
            if (jdbc.sql(
                "SELECT count(*) FROM les_forecast_evaluation_work WHERE route_version_id IN (SELECT id FROM route_version WHERE route_id=?)")
                .param(routeId).query(Integer.class).single() == 0) {
                break;
            }
        }
        double maximumLabel = 0;
        int settled = 0;
        List<Map<String, Object>> failures = new ArrayList<>();
        for (JsonNode row : cases) {
            List<Map<String, Object>> labels = jdbc
                .sql(
                    """
                        SELECT target_kind,scoring_state,label_value,reason,pre_observation_id,post_observation_id,
                          closure_observation_id,previous_post_observation_id,capacity,capacity_policy,known_at FROM les_forecast_evaluation_result
                        WHERE vehicle_observation_id=? AND target_stop_order=? AND label_version=?
                        """)
                .params(row.path("source_id").asLong(), row.path("target").asInt(), LesRouteStatistics.CONFIRMED_LABEL)
                .query().listOfRows();
            if (labels.size() != 1) {
                List<Map<String, Object>> pending = jdbc.sql(
                    "SELECT reason,target_kind FROM les_forecast_evaluation_work WHERE vehicle_observation_id=? AND target_stop_order=?")
                    .params(row.path("source_id").asLong(), row.path("target").asInt()).query().listOfRows();
                failures.add(Map.of("id", row.path("source_id").asLong(), "target", row.path("target").asInt(),
                    "label_count", labels.size(), "pending", pending));
            }
            for (Map<String, Object> label : labels) {
                if (!"SETTLED".equals(label.get("scoring_state"))
                    || !"AFTER_ALIGHTING_ESTIMATE".equals(label.get("target_kind"))) {
                    failures.add(Map.of("id", row.path("source_id").asLong(), "target", row.path("target").asInt(),
                        "state", label.toString()));
                    continue;
                }
                final double expected = row.path("expected_after").asDouble();
                maximumLabel = Math.max(maximumLabel,
                    Math.abs(((Number) label.get("label_value")).doubleValue() - expected));
                if (!matchesEvidence(row, label)) {
                    failures.add(Map.of("id", row.path("source_id").asLong(), "target", row.path("target").asInt(),
                        "evidence", label.toString()));
                }
                settled++;
            }
        }
        return Map.of("route", fixture.path("route").asText(), "cases", cases.size(), "settled_targets", settled,
            "max_feature_difference", maximumFeature, "max_prediction_difference", maximumPrediction,
            "max_label_difference", maximumLabel, "failures", failures,
            "passed", maximumFeature == 0 && maximumPrediction <= 1e-9 && maximumLabel <= 1e-8
                && settled == cases.size() && failures.isEmpty());
    }

    private static boolean matchesEvidence(JsonNode expected, Map<String, Object> actual) {
        if (((Number) actual.get("pre_observation_id")).longValue() != expected.path("expected_pre").asLong()) {
            return false;
        }
        if (((Number) actual.get("post_observation_id")).longValue() != expected.path("expected_post").asLong()) {
            return false;
        }
        if (((Number) actual.get("closure_observation_id")).longValue() != expected.path("expected_closure").asLong()) {
            return false;
        }
        if (!((Timestamp) actual.get("known_at")).toInstant()
            .equals(Instant.parse(expected.path("expected_known").asText()))) {
            return false;
        }
        return ((Number) actual.get("previous_post_observation_id")).longValue() == expected
            .path("expected_previous_post").asLong()
            && ((Number) actual.get("capacity")).intValue() == expected.path("expected_capacity").asInt()
            && actual.get("capacity_policy").equals("observed-vehicle-prefix-max-through-closure-v2");
    }

    private static long load(DataSource dataSource, JdbcClient jdbc, JsonNode fixture) throws Exception {
        final long route = jdbc.sql("""
            INSERT INTO route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name)
            VALUES(?,'GBIS',?,?, 'A','B') RETURNING id
            """).params(fixture.path("source_route_id").asText(), fixture.path("source_route_id").asText(),
            fixture.path("route").asText()).query(Long.class).single();
        for (JsonNode version : fixture.path("route_versions")) {
            jdbc.sql("""
                INSERT INTO route_version(id,route_id,turn_sequence,content_digest,valid_from,valid_to)
                OVERRIDING SYSTEM VALUE VALUES(?,?,?,?,?,?)
                """).params(version.path("id").asLong(), route, version.path("turn_sequence").asInt(),
                version.path("content_digest").asText(), at(version.path("valid_from").asText()),
                nullableAt(version.path("valid_to"))).update();
        }
        Map<Long, RouteStops> catalogs = catalogs(fixture);
        for (RouteStops catalog : catalogs.values()) {
            for (RouteStop stop : catalog.stops()) {
                jdbc.sql(
                    "INSERT INTO route_stop(route_version_id,stop_order,stop_id,name,direction,boarding_allowed) VALUES(?,?,?,?,?,?)")
                    .params(stop.routeVersionId(), stop.stopOrder(), stop.stopId(),
                        fixture.path("statistics").path("route_metadata").get(stop.stopOrder() - 1).path("name")
                            .asText(),
                        stop.direction().name(),
                        stop.boardingAllowed())
                    .update();
            }
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement batch = connection.prepareStatement(
                """
                    INSERT INTO observation_batch(id,route_version_id,scheduled_at,attempt_number,attempt_key,requested_at,
                      response_received_at,input_confirmed_at,outcome,stored_rows,normalization_version,collection_strategy_version,ingestion_origin)
                    OVERRIDING SYSTEM VALUE VALUES(?,?,?,1,?,?,?,?,'SUCCESS_ROWS',?,'normalization-v1.0.0','adaptive-kst-v1.0.1','LIVE')
                    """)) {
                for (JsonNode row : fixture.path("batches")) {
                    batch.setLong(1, row.path("id").asLong());
                    batch.setLong(2, row.path("rv").asLong());
                    batch.setObject(3, at(row.path("response_at").asText()));
                    batch.setString(4, "replay-" + row.path("id").asText());
                    batch.setObject(5, at(row.path("response_at").asText()));
                    batch.setObject(6, at(row.path("response_at").asText()));
                    batch.setObject(7, at(row.path("confirmed_at").asText()));
                    batch.setInt(8, row.path("stored_rows").asInt());
                    batch.addBatch();
                }
                batch.executeBatch();
            }
            Map<Long, LesObservation> rows = observations(fixture);
            for (JsonNode state : fixture.path("prefix")) {
                LesObservation previous = observation(state.path("previous"));
                rows.put(previous.observationId(), previous);
                if (state.has("capacity_observation")) {
                    LesObservation capacity = observation(state.path("capacity_observation"));
                    rows.put(capacity.observationId(), capacity);
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO vehicle_observation(id,observation_batch_id,route_version_id,source_row_number,vehicle_id,
                  stop_order,stop_id,running_state,remaining_seats,seat_unknown_reason,passed_stop_order,crowd_level)
                OVERRIDING SYSTEM VALUE VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
                Map<Long, Integer> counters = new HashMap<>();
                int pending = 0;
                for (LesObservation row : rows.values()) {
                    RouteStop stop = catalogs.get(row.routeVersionId()).stops().stream()
                        .filter(value -> value.stopOrder() == row.stopOrder()).findFirst().orElseThrow();
                    statement.setLong(1, row.observationId());
                    statement.setLong(2, row.batchId());
                    statement.setLong(3, row.routeVersionId());
                    statement.setInt(4, counters.merge(row.batchId(), 1, Integer::sum) - 1);
                    statement.setString(5, row.vehicleId());
                    statement.setInt(6, row.stopOrder());
                    statement.setString(7, stop.stopId());
                    statement.setInt(8, row.state());
                    statement.setObject(9, row.seats());
                    String reason = null;
                    if (row.seats() == null) {
                        reason = "NOT_REPORTED";
                    }
                    statement.setString(10, reason);
                    statement.setInt(11, row.passedStopOrder());
                    Integer crowd = null;
                    if (row.crowd() > 0) {
                        crowd = row.crowd();
                    }
                    statement.setObject(12, crowd);
                    statement.addBatch();
                    pending++;
                    if (pending % 2000 == 0) {
                        statement.executeBatch();
                    }
                }
                statement.executeBatch();
            }
            connection.commit();
        }
        return route;
    }

    private static Map<Long, RouteStops> catalogs(JsonNode fixture) {
        Map<Long, RouteStops> result = new HashMap<>();
        for (JsonNode version : fixture.path("route_versions")) {
            final long id = version.path("id").asLong();
            List<RouteStop> stops = new ArrayList<>();
            for (JsonNode stop : fixture.path("statistics").path("route_metadata")) {
                stops.add(new RouteStop(id, stop.path("order").asInt(), stop.path("id").asText(),
                    stop.path("boardingAllowed").asBoolean(), RouteDirection.valueOf(stop.path("direction").asText())));
            }
            result.put(id,
                new RouteStops(id, fixture.path("source_route_id").asText(), stops, fixture.path("route").asText()));
        }
        return result;
    }

    private static Map<Long, LesObservation> observations(JsonNode fixture) {
        Map<Long, LesObservation> result = new LinkedHashMap<>();
        fixture.path("observations").forEach(row -> {
            LesObservation observation = observation(row);
            result.put(observation.observationId(), observation);
        });
        return result;
    }

    private static Map<String, LesVehiclePrefix> prefixes(JsonNode fixture) {
        Map<String, LesVehiclePrefix> result = new HashMap<>();
        for (JsonNode state : fixture.path("prefix")) {
            LesObservation previous = observation(state.path("previous"));
            LesObservation capacity = null;
            if (state.has("capacity_observation")) {
                capacity = observation(state.path("capacity_observation"));
            }
            result.put(previous.vehicleId(), new LesVehiclePrefix((float) state.path("capacity").asDouble(),
                state.path("full_streak").asDouble(), previous, Instant.parse(state.path("entry_at").asText()),
                state.path("trip_id").asLong(), capacity));
        }
        return result;
    }

    private static LesObservation observation(JsonNode row) {
        Integer seats = null;
        if (!row.path("remaining_seats").isNull()) {
            seats = row.path("remaining_seats").asInt();
        }
        return new LesObservation(row.path("id").asLong(), row.path("batch_id").asLong(),
            row.path("batch_rank").asLong(),
            Instant.parse(row.path("received_at").asText()), row.path("vehicle_id").asText(),
            row.path("passed_stop_order").asInt(),
            row.path("stop_order").asInt(), row.path("running_state").asInt(), seats, row.path("crowd_level").asInt(),
            row.path("route_version_id").asLong(), Instant.parse(row.path("physical_received_at").asText()),
            row.path("down").asBoolean());
    }

    private static double[] vector(JsonNode values) {
        double[] result = new double[values.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = values.get(i).asDouble(Double.NaN);
        }
        return result;
    }

    private static double compare(JsonNode expected, double[] actual) {
        double maximum = 0;
        for (int i = 0; i < actual.length; i++) {
            final double value = expected.get(i).asDouble(Double.NaN);
            if (Double.isNaN(value) && Double.isNaN(actual[i])) {
                continue;
            }
            if (!Double.isFinite(value) || !Double.isFinite(actual[i])) {
                return Double.POSITIVE_INFINITY;
            }
            maximum = Math.max(maximum, Math.abs(value - actual[i]));
        }
        return maximum;
    }

    private static OffsetDateTime at(String value) {
        String normalized = value.replace(' ', 'T');
        if (normalized.endsWith("+00")) {
            normalized += ":00";
        }
        return OffsetDateTime.parse(normalized);
    }

    private static OffsetDateTime nullableAt(JsonNode value) {
        if (value.isNull()) {
            return null;
        }
        return at(value.asText());
    }
}
