package com.gustler.backend.forecasting.support;

import com.gustler.backend.forecasting.application.pipeline.PassLabel;
import com.gustler.backend.forecasting.application.pipeline.ScoredForecast;
import com.gustler.backend.forecasting.application.pipeline.ScoringResult;
import com.gustler.backend.forecasting.domain.model.SeatForecastResult;
import com.gustler.backend.forecasting.domain.model.VehicleStopTarget;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareFeatureSnapshot;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareFeatureVector;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareObservation;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwarePredictionAssembler;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareSeatForecastModel;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareSeatPredictor;
import com.gustler.backend.forecasting.domain.model.alighting.ExtendedAlightingPredictor;
import com.gustler.backend.forecasting.domain.pipeline.SourceDates;
import com.gustler.backend.forecasting.infrastructure.bundle.alighting.AlightingAwareInferenceBundle;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * 재생한 노선 하나의 사례를 저장된 결과와 견줘 노선 결과를 만든다.
 *
 * <p>사례마다 다음을 본다.
 * <ul>
 *   <li>입력: 그 배치 직전에 다시 뜬 것처럼 저장된 관측을 되감아 만든 상태로 예보 입력을 다시 만들어 자료의 입력과 견준다.
 *       차이가 조금이라도 있으면 통과하지 못한다. 그 상태로 낸 예측이 저장된 예측과 비트까지 같은지도 본다.</li>
 *   <li>예측: 예보가 이어 온 상태로 저장한 예측을, 자료의 입력을 모델에 넣은 값(시간 확장 사례는 자료에 적힌 값)과 견준다.</li>
 *   <li>정답: 예측별 결과 뷰({@code forecast_outcome})에서 그 예측의 상태·정답 값과 정답 계산에 쓰인 관측·정원을 읽어 자료의
 *       기대값과 견준다. 정답이 알려진 시각은 통과가 닫힌 시각이다.</li>
 * </ul>
 * 시간 확장 사례가 있으면 가장 먼저 채점된 시간 확장 사례 하나의 정답을, 저장된 예보·통과 기록만으로 채점 상태를 다시 만들어
 * 한 번 더 계산하고 저장된 값과 비트까지 같은지 본다.
 */
final class WorkerReplayVerification {
    private static final String CAPACITY_POLICY = "vehicle-capacity-snapshot-v1";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final double PREDICTION_TOLERANCE = 1e-9;
    private static final double LABEL_TOLERANCE = 1e-8;
    private static final long NO_BATCH = Long.MIN_VALUE;
    /** 결과 뷰의 열과 그 열에 맞아야 하는 사례의 기대값이다. */
    private static final Map<String, String> EVIDENCE = evidenceColumns();

    private final JdbcClient jdbc;
    private final WorkerReplayPipeline pipeline;
    private final AlightingAwareInferenceBundle bundle;
    private final AlightingAwareSeatForecastModel model;
    private final WorkerReplayFixture fixture;
    private final long routeId;
    private final Map<Long, WorkerReplayFixture.Batch> batches = new HashMap<>();
    private final Tally tally = new Tally();
    private long restartedBatchId = NO_BATCH;
    private WorkerReplayPipeline.Restarted restarted;

    /**
     * @param pipeline 이 노선의 배치를 이미 모두 흘린 예보·채점
     * @param bundle 재생한 번들
     * @param model 번들에서 읽은 모델
     * @param routeId 자료를 넣은 노선
     */
    WorkerReplayVerification(
        JdbcClient jdbc,
        WorkerReplayPipeline pipeline,
        AlightingAwareInferenceBundle bundle,
        AlightingAwareSeatForecastModel model,
        WorkerReplayFixture fixture,
        final long routeId
    ) {
        this.jdbc = jdbc;
        this.pipeline = pipeline;
        this.bundle = bundle;
        this.model = model;
        this.fixture = fixture;
        this.routeId = routeId;
        fixture.batches().forEach(batch -> batches.put(batch.id(), batch));
    }

    private static Map<String, String> evidenceColumns() {
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("arrival_observation_id", "expected_pre");
        columns.put("last_observation_id", "expected_post");
        columns.put("closure_observation_id", "expected_closure");
        columns.put("previous_last_observation_id", "expected_previous_post");
        columns.put("capacity", "expected_capacity");
        return columns;
    }

    /** 모든 사례를 견준 노선 결과를 돌려준다. 보고서의 {@code routes} 한 줄이다. */
    Map<String, Object> result() {
        reportUnappliedBatches();
        List<JsonNode> cases = fixture.cases();
        cases.forEach(this::verify);
        final boolean extended = cases.stream().anyMatch(row -> row.has("extended_inputs"));
        final int restored = restoreFirstSettled();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("route", fixture.route());
        result.put("cases", cases.size());
        result.put("settled_targets", tally.settled);
        result.put("max_feature_difference", tally.maximumFeature);
        result.put("max_prediction_difference", tally.maximumPrediction);
        result.put("max_label_difference", tally.maximumLabel);
        result.put("failures", tally.failures);
        result.put("extended_hours", extendedHours());
        result.put("expected_unsupported", tally.expectedUnsupported);
        result.put("restored_targets", restored);
        result.put("boundary_probes", tally.boundaryProbes);
        result.put("settled_boundaries", tally.settledBoundaries);
        result.put("passed", tally.passed(cases.size()) && hasRestoredWhenExtended(extended, restored));
        return result;
    }

    private static boolean hasRestoredWhenExtended(final boolean extended, final int restored) {
        return !extended || restored == 1;
    }

    private List<Map<String, Object>> extendedHours() {
        List<Map<String, Object>> hours = new ArrayList<>();
        tally.cells.forEach((cell, counts) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("route", fixture.route());
            row.put("cell", cell);
            row.put("published", counts[0]);
            row.put("settled", counts[1]);
            hours.add(row);
        });
        return hours;
    }

    /** 예보 실행이 남지 않은 배치가 있으면 그날을 끝까지 재생하지 못한 것이다. */
    private void reportUnappliedBatches() {
        Set<Long> applied = new HashSet<>(jdbc.sql("SELECT observation_batch_id FROM forecast_run WHERE route_id=?")
            .param(routeId).query(Long.class).list());
        List<Long> unapplied = fixture.batches().stream()
            .map(WorkerReplayFixture.Batch::id)
            .filter(batchId -> !applied.contains(batchId))
            .toList();
        if (unapplied.isEmpty()) {
            return;
        }
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("unapplied_batches", unapplied.size());
        failure.put("first_batch", unapplied.getFirst());
        tally.failures.add(failure);
    }

    private void verify(JsonNode row) {
        AlightingAwareObservation source = fixture.observation(row.path("source_id").asLong());
        final int target = row.path("target").asInt();
        Optional<Expected> expected = expected(row, source, target);
        expected.ifPresent(value -> tally.maximumFeature = Math.max(tally.maximumFeature, value.featureDifference()));
        List<Map<String, Object>> stored = storedOutcomes(source, target);
        if (stored.size() != 1) {
            Map<String, Object> failure = failure(row, "forecast_count", stored.size());
            failure.put("run", runOutcome(source.batchId()));
            tally.failures.add(failure);
            return;
        }
        Map<String, Object> outcome = stored.getFirst();
        expected.ifPresent(value -> comparePrediction(row, value, outcome));
        countPublished(row, outcome);
        settle(row, outcome);
    }

    private Optional<Expected> expected(
        JsonNode row,
        AlightingAwareObservation source,
        final int target
    ) {
        WorkerReplayPipeline.Restarted restart = restartedAt(source.batchId());
        if (restart == null) {
            tally.failures.add(failure(row, "restart", "FORECAST_RUN_MISSING"));
            return Optional.empty();
        }
        Optional<AlightingAwareFeatureSnapshot> snapshot = restart.snapshot(source.observationId());
        if (snapshot.isEmpty()) {
            tally.failures.add(failure(row, "restart", restart.withheldReason(source.observationId())));
            return Optional.empty();
        }
        Forecast forecast = expectedForecast(row, restart, snapshot.get(), target);
        return Optional.of(new Expected(forecast, restartedPrediction(restart, snapshot.get(), target).orElse(null)));
    }

    /** 사례는 기준 관측의 착수 시각 순서라 같은 배치가 이어진다. 직전 배치 하나만 들고 있는다. */
    private WorkerReplayPipeline.Restarted restartedAt(final long batchId) {
        if (restartedBatchId != batchId) {
            restarted = pipeline.restartedAt(batchId).orElse(null);
            restartedBatchId = batchId;
        }
        return restarted;
    }

    private Forecast expectedForecast(
        JsonNode row,
        WorkerReplayPipeline.Restarted restart,
        AlightingAwareFeatureSnapshot snapshot,
        final int target
    ) {
        if (row.has("extended_inputs")) {
            return expectedExtended(row, restart, snapshot, target);
        }
        AlightingAwareFeatureVector actual = snapshot.forTarget(target);
        if (row.path("boundary_probe").asBoolean(false)) {
            AlightingAwareSeatPredictor.Prediction rush = bundle.predictor()
                .predict(actual.seat88(), actual.probability80());
            return new Forecast(rush.afterAlightingSeats(), rush.departureFullProbability(), 0);
        }
        final double difference = Math.max(difference(row.path("seat88"), actual.seat88()),
            difference(row.path("probability80"), actual.probability80()));
        AlightingAwareSeatPredictor.Prediction expected = bundle.predictor()
            .predict(vector(row.path("seat88")), vector(row.path("probability80")));
        return new Forecast(expected.afterAlightingSeats(), expected.departureFullProbability(), difference);
    }

    private static Forecast expectedExtended(
        JsonNode row,
        WorkerReplayPipeline.Restarted restart,
        AlightingAwareFeatureSnapshot snapshot,
        final int target
    ) {
        VehicleStopTarget stop = restart.stops().targetsAheadOf(snapshot.observation()).stream()
            .filter(candidate -> candidate.stopOrder() == target)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("사례의 목표가 예보 대상 정류장이 아니다: " + target));
        LocalDate day = snapshot.source().receivedAt().atZone(SEOUL).toLocalDate();
        final boolean offDay = "off".equals(restart.statistics().dayType(day));
        final double difference = difference(row.path("extended_inputs"),
            ExtendedAlightingPredictor.inputs(snapshot, stop, offDay));
        return new Forecast(row.path("expected_seats").asDouble(), row.path("expected_full").asDouble(), difference);
    }

    private Optional<SeatForecastResult> restartedPrediction(
        WorkerReplayPipeline.Restarted restart,
        AlightingAwareFeatureSnapshot snapshot,
        final int target
    ) {
        return new AlightingAwarePredictionAssembler(model, restart.statistics(), restart.stops()).predict(snapshot)
            .flatMap(vehicle -> vehicle.targets().stream()
                .filter(candidate -> candidate.target().stopOrder() == target)
                .map(AlightingAwarePredictionAssembler.TargetPrediction::result)
                .findFirst());
    }

    private List<Map<String, Object>> storedOutcomes(AlightingAwareObservation source, final int target) {
        LocalDate sourceDate = SourceDates.of(batches.get(source.batchId()).responseAt());
        return jdbc.sql("""
            SELECT state,reason,label_value,expected_seats,full_probability,capacity,model_deployment_id,
              route_version_id,arrival_observation_id,last_observation_id,closure_observation_id,
              previous_last_observation_id
            FROM forecast_outcome
            WHERE source_date=? AND vehicle_observation_id=? AND target_stop_order=? AND route_id=?
            """).params(sourceDate, source.observationId(), target, routeId).query().listOfRows();
    }

    private String runOutcome(final long batchId) {
        return jdbc.sql("SELECT outcome FROM forecast_run WHERE observation_batch_id=?").param(batchId)
            .query(String.class).optional().orElse("MISSING");
    }

    private void comparePrediction(JsonNode row, Expected expected, Map<String, Object> outcome) {
        final double seats = number(outcome, "expected_seats");
        final double full = number(outcome, "full_probability");
        tally.maximumPrediction = Math.max(tally.maximumPrediction, Math.abs(seats - expected.forecast().seats()));
        tally.maximumPrediction = Math.max(tally.maximumPrediction, Math.abs(full - expected.forecast().full()));
        if (!expected.restartedSameAs(seats, full)) {
            tally.failures.add(failure(row, "restart_prediction", String.valueOf(expected.restarted())));
        }
    }

    private void countPublished(JsonNode row, Map<String, Object> outcome) {
        if (row.path("boundary_probe").asBoolean(false)) {
            requireDaytimeSettlement(outcome);
            tally.boundaryProbes++;
        }
        if (row.has("cell")) {
            tally.cells.computeIfAbsent(row.path("cell").asString(), cell -> new int[2])[0]++;
        }
    }

    /** 출퇴근 끝에 낸 예측의 통과가 낮 시간에 닫혀도 채점할 수 있게, 정산 근거에 낮 시간 계수가 남아 있어야 한다. */
    private void requireDaytimeSettlement(Map<String, Object> outcome) {
        final boolean kept = jdbc.sql("""
            SELECT (settlement_context -> CAST(? AS text) -> 'shared' -> 'extendedSettlement' -> 'work-day') IS NOT NULL
            FROM model_deployment WHERE id=?
            """).params(String.valueOf(outcome.get("route_version_id")), outcome.get("model_deployment_id"))
            .query(Boolean.class).single();
        if (!kept) {
            throw new IllegalStateException("경계 예측의 정산 근거에 낮 시간 계수가 없다");
        }
    }

    private void settle(JsonNode row, Map<String, Object> outcome) {
        if (isExpectedUnsupported(row, outcome)) {
            tally.expectedUnsupported++;
            return;
        }
        if (!"SETTLED".equals(outcome.get("state"))) {
            tally.failures.add(failure(row, "state", outcome.toString()));
            return;
        }
        final double label = number(outcome, "label_value");
        tally.maximumLabel = Math.max(tally.maximumLabel, Math.abs(label - row.path("expected_after").asDouble()));
        Instant knownAt = closedAt(row, outcome).orElse(null);
        if (!matchesEvidence(row, outcome, knownAt)) {
            Map<String, Object> evidence = new LinkedHashMap<>(outcome);
            evidence.put("known_at", String.valueOf(knownAt));
            tally.failures.add(failure(row, "evidence", evidence.toString()));
        }
        tally.settled++;
        countSettled(row);
        if (row.has("extended_inputs") && knownAt != null) {
            tally.settledExtended.add(new Settled(row.path("source_id").asLong(), row.path("target").asInt(), knownAt,
                label));
        }
    }

    private static boolean isExpectedUnsupported(JsonNode row, Map<String, Object> outcome) {
        if (!"UNSUPPORTED".equals(outcome.get("state"))) {
            return false;
        }
        JsonNode reason = row.path("expected_reason");
        return reason.isString() && reason.asString().equals(outcome.get("reason"));
    }

    /** 정답이 알려진 시각이다. 직전 통과는 항상 먼저 닫히므로 정답을 낸 통과가 닫힌 시각과 같다. */
    private Optional<Instant> closedAt(JsonNode row, Map<String, Object> outcome) {
        List<OffsetDateTime> closed = jdbc.sql("""
            SELECT closed_at FROM stop_pass
            WHERE route_id=? AND stop_order=? AND closure_observation_id=? AND last_observation_id=?
            """).params(routeId, row.path("target").asInt(), outcome.get("closure_observation_id"),
                outcome.get("last_observation_id"))
            .query(OffsetDateTime.class).list();
        if (closed.size() != 1) {
            return Optional.empty();
        }
        return Optional.of(closed.getFirst().toInstant());
    }

    private boolean matchesEvidence(JsonNode row, Map<String, Object> outcome, Instant knownAt) {
        final boolean sameObservations = EVIDENCE.entrySet().stream()
            .allMatch(column -> sameNumber(outcome.get(column.getKey()), row.path(column.getValue())));
        if (!sameObservations) {
            return false;
        }
        if (!Instant.parse(row.path("expected_known").asString()).equals(knownAt)) {
            return false;
        }
        return CAPACITY_POLICY.equals(capacityPolicy(outcome));
    }

    private static boolean sameNumber(Object stored, JsonNode expected) {
        return stored instanceof Number number && expected.isNumber() && number.longValue() == expected.asLong();
    }

    private String capacityPolicy(Map<String, Object> outcome) {
        return jdbc.sql("""
            SELECT settlement_context -> CAST(? AS text) -> 'shared' ->> 'capacityPolicy'
            FROM model_deployment WHERE id=?
            """).params(String.valueOf(outcome.get("route_version_id")), outcome.get("model_deployment_id"))
            .query(String.class).optional().orElse("");
    }

    private void countSettled(JsonNode row) {
        if (!row.has("cell")) {
            return;
        }
        String origin = row.path("cell").asString();
        tally.cells.get(origin)[1]++;
        if (row.has("settlement_cell") && !origin.equals(row.path("settlement_cell").asString())) {
            tally.settledBoundaries.merge(origin + " -> " + row.path("settlement_cell").asString(), 1, Integer::sum);
        }
    }

    /**
     * 가장 먼저 채점된 시간 확장 사례 하나의 정답을 저장된 기록만으로 다시 계산한다.
     *
     * @return 다시 계산한 정답이 저장된 정답과 같으면 1, 시간 확장 사례가 없거나 다시 계산되지 않았으면 0
     * @throws IllegalStateException 다시 계산한 정답이 저장된 정답과 다르면 던진다
     */
    private int restoreFirstSettled() {
        Optional<Settled> first = tally.settledExtended.stream().min(Settled.ORDER);
        if (first.isEmpty()) {
            return 0;
        }
        Settled settled = first.get();
        ScoringResult rescored = pipeline.rescoredThrough(routeId, settled.closedAt());
        Optional<PassLabel> label = rescored.scored().stream()
            .filter(settled::isForecastOf)
            .findFirst()
            .flatMap(scored -> rescored.labels().stream()
                .filter(candidate -> labelKey(candidate).equals(labelKey(scored)))
                .findFirst());
        if (label.isEmpty()) {
            return 0;
        }
        if (!settled.hasSameLabelAs(label.get())) {
            throw new IllegalStateException("저장된 기록으로 다시 채점한 정답이 저장된 정답과 다르다: "
                + settled.sourceId() + "/" + settled.target());
        }
        return 1;
    }

    private static List<Object> labelKey(PassLabel label) {
        return List.of(label.sourceDate(), label.routeId(), label.tripStartObservationId(), label.stopOrder(),
            label.capacity(), label.labelVersion(), label.modelDeploymentId());
    }

    private static List<Object> labelKey(ScoredForecast scored) {
        return List.of(scored.passSourceDate(), scored.routeId(), scored.tripStartObservationId(), scored.stopOrder(),
            scored.capacity(), scored.labelVersion(), scored.modelDeploymentId());
    }

    private static Map<String, Object> failure(JsonNode row, String kind, Object detail) {
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("id", row.path("source_id").asLong());
        failure.put("target", row.path("target").asInt());
        failure.put(kind, detail);
        return failure;
    }

    private static double number(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).doubleValue();
    }

    private static double[] vector(JsonNode values) {
        double[] result = new double[values.size()];
        for (int index = 0; index < result.length; index++) {
            result[index] = values.get(index).asDouble(Double.NaN);
        }
        return result;
    }

    /** 가장 큰 절대 차이다. 한쪽만 값이 없거나 유한하지 않으면 무한대다. */
    private static double difference(JsonNode expected, double[] actual) {
        if (expected.size() != actual.length) {
            throw new IllegalStateException("사례의 입력 길이가 다르다: " + expected.size() + ", " + actual.length);
        }
        double maximum = 0;
        for (int index = 0; index < actual.length; index++) {
            final double value = expected.get(index).asDouble(Double.NaN);
            if (Double.isNaN(value) && Double.isNaN(actual[index])) {
                continue;
            }
            if (!Double.isFinite(value) || !Double.isFinite(actual[index])) {
                return Double.POSITIVE_INFINITY;
            }
            maximum = Math.max(maximum, Math.abs(value - actual[index]));
        }
        return maximum;
    }

    /** 사례의 기대 예측과, 자료의 입력과 다시 만든 입력의 가장 큰 차이다. */
    private record Forecast(double seats, double full, double featureDifference) {
    }

    /**
     * 사례 하나의 기대값이다.
     *
     * @param restarted 되감아 만든 상태로 낸 그 목표의 예측. 그 상태가 이 목표를 예측하지 않았으면 비어 있다
     */
    private record Expected(Forecast forecast, SeatForecastResult restarted) {
        private double featureDifference() {
            return forecast.featureDifference();
        }

        private boolean restartedSameAs(final double seats, final double full) {
            if (restarted == null) {
                return false;
            }
            return Double.compare(restarted.expectedSeats(), seats) == 0
                && Double.compare(restarted.fullChance(), full) == 0;
        }
    }

    /** 정답을 받은 시간 확장 사례 하나다. */
    private record Settled(long sourceId, int target, Instant closedAt, double label) {
        private static final Comparator<Settled> ORDER = Comparator.comparing(Settled::closedAt)
            .thenComparingLong(Settled::sourceId)
            .thenComparingInt(Settled::target);

        private boolean isForecastOf(ScoredForecast scored) {
            return scored.vehicleObservationId() == sourceId && scored.targetStopOrder() == target;
        }

        private boolean hasSameLabelAs(PassLabel rescored) {
            return rescored.state() == PassLabel.State.SETTLED && Double.compare(rescored.labelValue(), label) == 0;
        }
    }

    /** 사례를 견주며 쌓는 수와 어긋난 사례 목록이다. */
    private static final class Tally {
        private final List<Map<String, Object>> failures = new ArrayList<>();
        private final Map<String, int[]> cells = new LinkedHashMap<>();
        private final Map<String, Integer> settledBoundaries = new LinkedHashMap<>();
        private final List<Settled> settledExtended = new ArrayList<>();
        private double maximumFeature;
        private double maximumPrediction;
        private double maximumLabel;
        private int settled;
        private int expectedUnsupported;
        private int boundaryProbes;

        private boolean passed(final int cases) {
            if (cases == 0 || settled + expectedUnsupported != cases) {
                return false;
            }
            return failures.isEmpty() && withinTolerance();
        }

        private boolean withinTolerance() {
            if (maximumFeature != 0) {
                return false;
            }
            return maximumPrediction <= PREDICTION_TOLERANCE && maximumLabel <= LABEL_TOLERANCE;
        }
    }
}
