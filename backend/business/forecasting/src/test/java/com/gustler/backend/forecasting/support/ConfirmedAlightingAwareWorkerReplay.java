package com.gustler.backend.forecasting.support;

import com.gustler.backend.forecasting.api.evaluation.EvaluationArchiveObjects;
import com.gustler.backend.forecasting.application.RouteWorkAccess;
import com.gustler.backend.forecasting.application.deployment.ActiveForecastRuntimeResolver;
import com.gustler.backend.forecasting.application.deployment.BundleActivation;
import com.gustler.backend.forecasting.application.deployment.LoadedModelRegistry;
import com.gustler.backend.forecasting.application.evaluation.AlightingAwareEvaluationArchiveService;
import com.gustler.backend.forecasting.application.publication.ForecastBatchCommitter;
import com.gustler.backend.forecasting.application.publication.ForecastBatchWriter;
import com.gustler.backend.forecasting.domain.deployment.ModelDeploymentRepository;
import com.gustler.backend.forecasting.domain.deployment.ModelRelease;
import com.gustler.backend.forecasting.domain.deployment.RuntimeSnapshot;
import com.gustler.backend.forecasting.domain.deployment.SupportedForecastScope;
import com.gustler.backend.forecasting.domain.model.RouteDirection;
import com.gustler.backend.forecasting.domain.model.RouteStop;
import com.gustler.backend.forecasting.domain.model.RouteStops;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareFeatureSnapshot;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareFeatureVector;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareObservation;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareSeatForecastModel;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareSeatPredictor;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareVehiclePrefix;
import com.gustler.backend.forecasting.domain.publication.PendingForecastBatch;
import com.gustler.backend.forecasting.infrastructure.alighting.AlightingAwareHistorySeedReader;
import com.gustler.backend.forecasting.infrastructure.alighting.ConfirmedAlightingAwareInput;
import com.gustler.backend.forecasting.infrastructure.alighting.JdbcAlightingAwareFeatureQuery;
import com.gustler.backend.forecasting.infrastructure.bundle.alighting.AlightingAwareInferenceBundle;
import com.gustler.backend.forecasting.infrastructure.bundle.alighting.AlightingAwareServingBundle;
import com.gustler.backend.forecasting.infrastructure.capacity.JdbcVehicleCapacityStore;
import com.gustler.backend.forecasting.infrastructure.capacity.VehicleCapacityCache;
import com.gustler.backend.forecasting.infrastructure.jdbc.JdbcAlightingAwareEvaluationArchiveStore;
import com.gustler.backend.forecasting.infrastructure.jdbc.JdbcAlightingAwareForecastEvaluation;
import java.io.IOException;
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
public final class ConfirmedAlightingAwareWorkerReplay {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public static void main(String[] args) throws Exception {
        if ((args.length < 3 || args.length > 4) || Files.exists(Path.of(args[2]))) {
            throw new IllegalArgumentException("fixtures, inactive bundle, new output directory required");
        }
        JsonNode manifest = JSON.readTree(Files.readAllBytes(Path.of(args[0]).resolve("manifest.json")));
        if (!manifest.path("capacity_policy").asText().equals("vehicle-capacity-snapshot-v1")
            || !manifest.path("label_version").asText().equals("alighting-confirmed-capacity-snapshot-v3")) {
            throw new IllegalArgumentException("이전 정원 정책의 재생 자료다. 새 정원 스냅샷 기준으로 독립 정답을 준비해야 한다");
        }
        Path output = Path.of(args[2]);
        Files.createDirectory(output);
        AlightingAwareInferenceBundle bundle = AlightingAwareInferenceBundle.load(Path.of(args[1]));
        AlightingAwareSeatForecastModel model = AlightingAwareServingBundle.readModel(bundle);
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
            ModelRelease release = ModelRelease.alighting("local-confirmed-replay", bundle.digest(),
                "2026-10-22T00:00:00+09:00", new SupportedForecastScope(bundle.routes()), model);
            tx.executeWithoutResult(status -> activation.activate(() -> release));
            RuntimeSnapshot runtime = new ActiveForecastRuntimeResolver(deployments, registry)
                .resolveActive().orElseThrow();
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
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("passed", passed);
        report.put("routes", results);
        report.put("bundle_manifest_sha256", bundle.digest());
        report.put("verification_code_sha", System.getProperty("alighting.replay.code-sha", ""));
        report.put("serving_ready", false);
        report.put("settlement_scope", "after_alighting_only");
        report.put("runtime_capacity_policy", "vehicle-capacity-snapshot-v1");
        report.put("runtime_label_version", "alighting-confirmed-capacity-snapshot-v3");
        report.put("runtime_calculation_version", "alighting-online-capacity-snapshot-v2");
        report.put("quality_scope",
            "Accepted local observations with simulated collection capacity history; production collection rejection state unavailable");
        report.put("input_scope", "Complete day observations and source-derived midnight prefixes; not full DB bootstrap");
        Files.writeString(output.resolve("report.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        if (!passed) {
            throw new IllegalStateException("Worker replay mismatch");
        }
    }

    private static Map<String, Object> replay(
        ConfigurableApplicationContext context,
        JsonNode fixture,
        Path output,
        AlightingAwareInferenceBundle bundle,
        AlightingAwareSeatForecastModel model,
        RuntimeSnapshot runtime,
        TransactionTemplate tx
    ) throws Exception {
        JdbcClient jdbc = context.getBean(JdbcClient.class);
        RouteWorkAccess quality = context.getBean(RouteWorkAccess.class);
        final long routeId = load(context.getBean(DataSource.class), jdbc, fixture);
        Map<Long, AlightingAwareObservation> byId = observations(fixture);
        Map<String, AlightingAwareVehiclePrefix> prefix = prefixes(fixture);
        List<JsonNode> cases = new ArrayList<>();
        fixture.path("cases").forEach(cases::add);
        cases.sort(Comparator.comparing(row -> byId.get(row.path("source_id").asLong()).receivedAt()));
        Path seedRoot = output.resolve("checkpoints-" + fixture.path("route").asText());
        String directory = seedRoot.toString();
        Map<Long, RouteStops> catalogs = catalogs(fixture);
        for (RouteStops stops : catalogs.values()) {
            AlightingAwareHistorySeedReader reader = AlightingAwareHistorySeedReader.confirmed(directory, 250_000);
            JsonNode anchor = fixture.path("anchor");
            reader.write(stops.routeVersionId(), new AlightingAwareHistorySeedReader.Seed(List.of(), anchor.path("batch").asLong(),
                anchor.path("rank").asLong(), Instant.parse(anchor.path("time").asText()), prefix, List.of()));
        }
        Map<String, int[]> counts = new LinkedHashMap<>();
        double maximumFeature = 0;
        double maximumPrediction = 0;
        for (JsonNode row : cases) {
            AlightingAwareObservation source = byId.get(row.path("source_id").asLong());
            RouteStops stops = catalogs.get(source.routeVersionId());
            AlightingAwareRouteStatistics statistics = model.statistics(stops);
            JdbcAlightingAwareFeatureQuery query = new JdbcAlightingAwareFeatureQuery(jdbc, new VehicleCapacityCache(
            new JdbcVehicleCapacityStore(jdbc)), "", 250_000, true, directory);
            final boolean extended = row.has("extended_inputs");
            ReplayForecast expected = expectedForecast(row, query, source, stops, statistics, bundle);
            maximumFeature = Math.max(maximumFeature, expected.featureDifference());
            ForecastBatchWriter writer = new ForecastBatchWriter(
                context.getBean(ForecastBatchCommitter.class), Clock.fixed(source.receivedAt(), ZoneOffset.UTC),
                quality);
            writer.setAlightingAwareFeatures(query);
            writer.setConfirmedInput(context.getBean(ConfirmedAlightingAwareInput.class));
            PendingForecastBatch batch = new PendingForecastBatch(source.batchId(), source.routeVersionId(), routeId,
                source.sourceReceivedAt());
            writer.writeForecastsOf(batch, stops, runtime);
            final double prediction = jdbc
                .sql("SELECT expected_seats FROM seat_forecast WHERE vehicle_observation_id=? AND target_stop_order=?")
                .params(source.observationId(), row.path("target").asInt()).query(Double.class).single();
            maximumPrediction = Math.max(maximumPrediction, Math.abs(prediction - expected.seats()));
            final double fullProbability = jdbc
                .sql("SELECT seat_full_chance FROM seat_forecast WHERE vehicle_observation_id=? AND target_stop_order=?")
                .params(source.observationId(), row.path("target").asInt()).query(Double.class).single();
            maximumPrediction = Math.max(maximumPrediction,
                Math.abs(fullProbability - expected.full()));
            if (extended) {
                String evidence = jdbc.sql("""
                    SELECT feature_snapshot::text FROM seat_forecast WHERE vehicle_observation_id=? AND target_stop_order=?
                    """).params(source.observationId(), row.path("target").asInt()).query(String.class).single();
                maximumFeature = Math.max(maximumFeature,
                    compare(row.path("extended_inputs"), vector(JSON.readTree(evidence).path("extendedInputs"))));
            }
            if (row.has("cell")) {
                counts.computeIfAbsent(row.path("cell").asText(), cell -> new int[2])[0]++;
            }
            // A restart must use the saved cursor and preserve the first confirmation.
            writer.writeForecastsOf(batch, stops, runtime);
            Instant confirmed = jdbc.sql("SELECT input_confirmed_at FROM observation_batch WHERE id=?")
                .param(source.batchId()).query(OffsetDateTime.class).single().toInstant();
            if (!confirmed.equals(source.receivedAt())) {
                throw new IllegalStateException("Confirmation moved during republish");
            }
        }
        JdbcAlightingAwareForecastEvaluation evaluation = new JdbcAlightingAwareForecastEvaluation(jdbc, quality,
            Clock.fixed(Instant.parse(fixture.path("day").asText() + "T23:59:59+09:00"), ZoneOffset.UTC),
            context.getBean(PlatformTransactionManager.class), 50_000, 150_000);
        final int initialWork = jdbc.sql("SELECT count(*) FROM alighting_forecast_evaluation_work")
            .query(Integer.class).single();
        final int maximumTicks = (initialWork + 99) / 100 + 2;
        for (int tick = 0; tick < maximumTicks; tick++) {
            evaluation.settle();
            if (jdbc.sql(
                "SELECT count(*) FROM alighting_forecast_evaluation_work WHERE route_version_id IN (SELECT id FROM route_version WHERE route_id=?)")
                .param(routeId).query(Integer.class).single() == 0) {
                break;
            }
        }
        double maximumLabel = 0;
        int settled = 0;
        int expectedUnsupported = 0;
        List<Map<String, Object>> failures = new ArrayList<>();
        for (JsonNode row : cases) {
            List<Map<String, Object>> labels = jdbc
                .sql(
                    """
                        SELECT target_kind,scoring_state,label_value,reason,pre_observation_id,post_observation_id,
                          closure_observation_id,previous_post_observation_id,capacity,capacity_policy,known_at FROM alighting_forecast_evaluation_result
                        WHERE vehicle_observation_id=? AND target_stop_order=? AND label_version=?
                        """)
                .params(row.path("source_id").asLong(), row.path("target").asInt(), "alighting-confirmed-capacity-snapshot-v3")
                .query().listOfRows();
            if (labels.size() != 1) {
                List<Map<String, Object>> pending = jdbc.sql(
                    "SELECT reason,target_kind FROM alighting_forecast_evaluation_work WHERE vehicle_observation_id=? AND target_stop_order=?")
                    .params(row.path("source_id").asLong(), row.path("target").asInt()).query().listOfRows();
                failures.add(Map.of("id", row.path("source_id").asLong(), "target", row.path("target").asInt(),
                    "label_count", labels.size(), "pending", pending));
            }
            for (Map<String, Object> label : labels) {
                if ("UNSUPPORTED".equals(label.get("scoring_state"))
                    && row.path("expected_reason").isString()
                    && row.path("expected_reason").asText().equals(label.get("reason"))) {
                    expectedUnsupported++;
                    continue;
                }
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
                if (row.has("cell")) {
                    counts.get(row.path("cell").asText())[1]++;
                }
            }
        }
        String route = fixture.path("route").asText();
        List<Map<String, Object>> cells = counts.entrySet().stream()
            .map(entry -> Map.<String, Object>of("route", route, "cell", entry.getKey(),
                "published", entry.getValue()[0], "settled", entry.getValue()[1]))
            .toList();
        final boolean extendedReplay = cases.stream().anyMatch(row -> row.has("extended_inputs"));
        final int restored = restoreFirstSettled(jdbc, tx.getTransactionManager(), routeId, extendedReplay);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("route", route);
        result.put("cases", cases.size());
        result.put("settled_targets", settled);
        result.put("max_feature_difference", maximumFeature);
        result.put("max_prediction_difference", maximumPrediction);
        result.put("max_label_difference", maximumLabel);
        result.put("failures", failures);
        result.put("extended_hours", cells);
        result.put("expected_unsupported", expectedUnsupported);
        result.put("restored_targets", restored);
        result.put("passed", maximumFeature == 0 && maximumPrediction <= 1e-9 && maximumLabel <= 1e-8
            && !cases.isEmpty() && settled + expectedUnsupported == cases.size() && failures.isEmpty()
            && (!extendedReplay || restored == 1));
        return result;
    }

    private static ReplayForecast expectedForecast(
        JsonNode row,
        JdbcAlightingAwareFeatureQuery query,
        AlightingAwareObservation source,
        RouteStops stops,
        AlightingAwareRouteStatistics statistics,
        AlightingAwareInferenceBundle bundle
    ) {
        if (row.has("extended_inputs")) {
            return new ReplayForecast(row.get("expected_seats").asDouble(), row.get("expected_full").asDouble(), 0);
        }
        AlightingAwareFeatureSnapshot snapshot = query.read(source.batchId(), stops, statistics).get(source.observationId());
        if (snapshot == null) {
            throw new IllegalStateException("Missing real source " + source.observationId());
        }
        AlightingAwareFeatureVector actual = snapshot.forTarget(row.path("target").asInt());
        final double difference = Math.max(compare(row.path("seat88"), actual.seat88()),
            compare(row.path("probability80"), actual.probability80()));
        AlightingAwareSeatPredictor.Prediction expected = bundle.predictor().predict(vector(row.path("seat88")),
            vector(row.path("probability80")));
        return new ReplayForecast(expected.afterAlightingSeats(), expected.departureFullProbability(), difference);
    }

    private record ReplayForecast(double seats, double full, double featureDifference) {
    }

    private static int restoreFirstSettled(
        JdbcClient jdbc, PlatformTransactionManager transactions, final long route, final boolean extendedReplay
    ) {
        if (!extendedReplay) {
            return 0;
        }
        List<Map<String, Object>> keys = jdbc.sql("""
            SELECT result.vehicle_observation_id,result.target_stop_order,result.target_kind,result.label_version,
                   result.quality_revision,result.label_value
            FROM alighting_forecast_evaluation_result result
            JOIN route_version version ON version.id=result.route_version_id
            JOIN seat_forecast forecast USING(vehicle_observation_id,target_stop_order)
            WHERE version.route_id=? AND result.scoring_state='SETTLED' AND forecast.feature_snapshot->'extendedInputs' IS NOT NULL
            ORDER BY result.scored_at,result.vehicle_observation_id,result.target_stop_order LIMIT 1
            """).param(route).query().listOfRows();
        if (keys.isEmpty()) {
            return 0;
        }
        Map<String, Object> key = keys.getFirst();
        Map<String, byte[]> files = new HashMap<>();
        AlightingAwareEvaluationArchiveService archive = new AlightingAwareEvaluationArchiveService(
            new JdbcAlightingAwareEvaluationArchiveStore(jdbc, transactions), archiveObjects(files), true, 500);
        for (int page = 0; page < 20; page++) {
            archive.advance();
            if (jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM alighting_evaluation_archive WHERE vehicle_observation_id=?
                    AND target_stop_order=? AND storage_state='PURGED')
                """).params(key.get("vehicle_observation_id"), key.get("target_stop_order")).query(Boolean.class).single()) {
                return restoreAndCompare(jdbc, archive, key);
            }
        }
        return 0;
    }

    private static int restoreAndCompare(
        JdbcClient jdbc, AlightingAwareEvaluationArchiveService archive, Map<String, Object> key
    ) {
        final long observation = ((Number) key.get("vehicle_observation_id")).longValue();
        final int stop = ((Number) key.get("target_stop_order")).intValue();
        final long revision = ((Number) key.get("quality_revision")).longValue();
        String target = key.get("target_kind").toString();
        String version = key.get("label_version").toString();
        final int restored = archive.restore(observation, stop, target, version, revision);
        final double after = jdbc.sql("""
            SELECT label_value FROM alighting_forecast_evaluation_result
            WHERE vehicle_observation_id=? AND target_stop_order=? AND target_kind=? AND label_version=? AND quality_revision=?
            """).params(observation, stop, target, version, revision).query(Double.class).single();
        if (after != ((Number) key.get("label_value")).doubleValue()) {
            throw new IllegalStateException("Restored settlement differs");
        }
        return restored;
    }

    private static EvaluationArchiveObjects archiveObjects(Map<String, byte[]> files) {
        return new EvaluationArchiveObjects() {
            @Override
            public void putIfAbsent(String bucket, String key, Path source, String checksum) {
                try {
                    files.putIfAbsent(key, Files.readAllBytes(source));
                } catch (IOException failure) {
                    throw new IllegalStateException(failure);
                }
            }

            @Override
            public void download(String bucket, String key, Path destination, final long limit) {
                byte[] content = files.get(key);
                if (content == null || content.length > limit) {
                    throw new IllegalStateException("Replay archive object missing or oversized");
                }
                try {
                    Files.write(destination, content);
                } catch (IOException failure) {
                    throw new IllegalStateException(failure);
                }
            }
        };
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
            && actual.get("capacity_policy").equals("vehicle-capacity-snapshot-v1");
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
            Map<Long, AlightingAwareObservation> rows = observations(fixture);
            for (JsonNode state : fixture.path("prefix")) {
                AlightingAwareObservation previous = observation(state.path("previous"));
                rows.put(previous.observationId(), previous);
                if (state.has("capacity_observation")) {
                    AlightingAwareObservation capacity = observation(state.path("capacity_observation"));
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
                for (AlightingAwareObservation row : rows.values()) {
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
            try (var capacities = connection.createStatement()) {
                capacities.executeUpdate("""
                    INSERT INTO vehicle_capacity_history(source_id,vehicle_id,evidence_at,evidence_batch_id,capacity)
                    SELECT 'GBIS',o.vehicle_id,b.input_confirmed_at,b.id,
                        max(o.remaining_seats) OVER (PARTITION BY o.vehicle_id ORDER BY b.input_confirmed_at,b.id)
                    FROM vehicle_observation o JOIN observation_batch b ON b.id=o.observation_batch_id
                    WHERE o.vehicle_id IS NOT NULL AND o.remaining_seats BETWEEN 1 AND 70
                    ON CONFLICT DO NOTHING
                    """);
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

    private static Map<Long, AlightingAwareObservation> observations(JsonNode fixture) {
        Map<Long, AlightingAwareObservation> result = new LinkedHashMap<>();
        fixture.path("observations").forEach(row -> {
            AlightingAwareObservation observation = observation(row);
            result.put(observation.observationId(), observation);
        });
        return result;
    }

    private static Map<String, AlightingAwareVehiclePrefix> prefixes(JsonNode fixture) {
        Map<String, AlightingAwareVehiclePrefix> result = new HashMap<>();
        for (JsonNode state : fixture.path("prefix")) {
            AlightingAwareObservation previous = observation(state.path("previous"));
            AlightingAwareObservation capacity = null;
            if (state.has("capacity_observation")) {
                capacity = observation(state.path("capacity_observation"));
            }
            result.put(previous.vehicleId(), new AlightingAwareVehiclePrefix((float) state.path("capacity").asDouble(),
                state.path("full_streak").asDouble(), previous, Instant.parse(state.path("entry_at").asText()),
                state.path("trip_id").asLong(), capacity));
        }
        return result;
    }

    private static AlightingAwareObservation observation(JsonNode row) {
        Integer seats = null;
        if (!row.path("remaining_seats").isNull()) {
            seats = row.path("remaining_seats").asInt();
        }
        return new AlightingAwareObservation(row.path("id").asLong(), row.path("batch_id").asLong(),
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
        if (expected.size() != actual.length) {
            throw new IllegalStateException("Replay input vector length mismatch");
        }
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
