package com.gustler.backend.forecasting.support;

import com.gustler.backend.forecasting.domain.model.SeatForecastResult;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareFeatureSnapshot;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareFeatureVector;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareHistoryCompactor;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareObservation;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwarePassEvent;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareVehiclePrefix;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingPosterior;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingTimeCell;
import com.gustler.backend.forecasting.domain.model.alighting.ExtendedAlightingPredictor;
import com.gustler.backend.forecasting.domain.model.alighting.NumericTreeEnsemble;
import com.gustler.backend.forecasting.infrastructure.alighting.AlightingAwareStatisticsReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 합성 노선 하나의 하루치 재생 자료와 그 노선의 비활성 번들을 만든다. 실제 운영 자료가 아니다.
 *
 * <p>정류장 여덟 개를 한 방향으로 지나는 차량 두 대가 30초 간격 배치마다 관측되고, 두 배치마다 한 정류장씩 나아간다. 뒤차는
 * 앞차보다 여섯 배치 늦게 출발하므로 뒤차가 지나는 정류장마다 앞차의 통과가 같은 날의 직전 통과가 된다. 그날 앞의 관측으로는
 * 차량마다 전날의 마지막 관측과 정원을 올린 관측을 둔다.
 *
 * <p>사례의 기대값은 재생이 쓰는 경로와 따로 계산한다. 출퇴근 사례의 입력은 하루치 관측 전체와 그날 앞의 차량 상태를 도메인 입력
 * 계산에 그대로 넣은 값이고, 시간 확장 사례의 입력과 예측은 관측에서 바로 읽은 값을 같은 트리에 넣은 값이다. 정답은 통과의 관측을
 * 배치 번호로 고른 뒤 하차 추정식에 넣은 값이다.
 */
final class SyntheticWorkerReplay {
    static final String ROUTE = "3330";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final String SOURCE_ROUTE_ID = "204000057";
    private static final String DAY = "2026-11-04";
    private static final String CONFIRMED_PROFILE = "alighting-confirmed-closure-v2";
    private static final String CAPACITY_POLICY = "vehicle-capacity-snapshot-v1";
    private static final String LABEL_VERSION = "alighting-confirmed-capacity-snapshot-v3";
    private static final Instant RUSH_START = Instant.parse("2026-11-03T22:00:00Z");
    private static final Instant DAYTIME_START = Instant.parse("2026-11-04T02:00:00Z");
    private static final Duration CONFIRMATION_DELAY = Duration.of(2_345_678, ChronoUnit.MICROS);
    private static final long VERSION = 880_000_001L;
    private static final long FIRST_BATCH = 880_100_000L;
    private static final long FIRST_OBSERVATION = 880_200_000L;
    private static final long FIRST_RANK = 200;
    private static final int STOPS = 8;
    private static final int CAPACITY = 45;
    private static final int BATCHES = 22;
    private static final Vehicle LEADER = new Vehicle("replay-veh-a", 0, 30, 2);
    private static final Vehicle FOLLOWER = new Vehicle("replay-veh-b", 6, 25, 1);
    private static final Tree BASE = Tree.split("regression", 88, new double[] {10}, 52, 20, -2, 3);
    private static final Tree GATE = Tree.leaf("multiclass", 88, new double[] {0, 0, 0});
    private static final Tree POOL = Tree.split("binary", 80, new double[] {0}, 79, 2, -0.4, 0.6);
    private static final Tree EXTENDED_SEATS = Tree.split("regression", 6, new double[] {12}, 0, 26, -3, 2.5);
    private static final Tree EXTENDED_FULL = Tree.split("binary", 6, new double[] {0}, 3, 2, -0.3, 0.4);

    private final Instant start;
    private final boolean extended;
    private final ObjectNode statistics = statistics(ROUTE);
    private final Map<String, AlightingAwareObservation> observations = new LinkedHashMap<>();
    private final Map<String, AlightingAwareVehiclePrefix> prefix = new LinkedHashMap<>();
    private final List<ObjectNode> cases = new ArrayList<>();
    private final Map<Long, Instant> lateConfirmations = new HashMap<>();
    private long nextObservationId = FIRST_OBSERVATION;

    private SyntheticWorkerReplay(Instant start, final boolean extended) {
        this.start = start;
        this.extended = extended;
        for (Vehicle vehicle : List.of(LEADER, FOLLOWER)) {
            AlightingAwareObservation capacity = earlier(vehicle, FIRST_BATCH, Duration.ofHours(10), 3, CAPACITY);
            AlightingAwareObservation previous = earlier(vehicle, FIRST_BATCH + 1, Duration.ofHours(9), STOPS, 20);
            prefix.put(vehicle.id(), new AlightingAwareVehiclePrefix(CAPACITY, 0, previous, previous.receivedAt(),
                previous.observationId(), capacity));
        }
        for (int batch = 0; batch < BATCHES; batch++) {
            observe(LEADER, batch);
            observe(FOLLOWER, batch);
        }
    }

    /** 평일 아침 출퇴근 시간의 자료다. 사례 둘은 뒤차의 예측이고 모두 정답을 받는다. */
    static SyntheticWorkerReplay rush() {
        SyntheticWorkerReplay replay = new SyntheticWorkerReplay(RUSH_START, false);
        replay.cases.add(replay.rushCase(6, 4));
        replay.cases.add(replay.rushCase(8, 3));
        return replay;
    }

    /**
     * 평일 낮의 자료다. 뒤차의 예측 하나는 정답을 받고, 앞차의 예측 하나는 그날 직전 통과가 없어 지원 안 함으로 닫힌다.
     */
    static SyntheticWorkerReplay extended() {
        SyntheticWorkerReplay replay = new SyntheticWorkerReplay(DAYTIME_START, true);
        ObjectNode settled = replay.extendedCase(FOLLOWER, 6, 4);
        replay.addSettlement(settled, FOLLOWER, 4);
        replay.cases.add(settled);
        ObjectNode withoutPrevious = replay.extendedCase(LEADER, 0, 3);
        withoutPrevious.put("expected_reason", "NO_PREVIOUS_PASS");
        replay.cases.add(withoutPrevious);
        return replay;
    }

    /** 자료를 쓰기 전에 사례를 고칠 때 쓴다. */
    ObjectNode caseAt(final int index) {
        return cases.get(index);
    }

    /**
     * 배치 하나의 착수 시각만 수신 뒤 {@code delay}로 늦춰 쓴다. 다음 배치보다 늦게 착수하게 하면 수신 순서와 착수 순서가
     * 뒤집힌 자료가 된다. 사례의 기대값은 고치지 않는다.
     */
    void confirmLate(final int batch, Duration delay) {
        lateConfirmations.put(batchId(batch), responseAt(batch).plus(delay));
    }

    /** 노선 파일과 {@code manifest.json}을 쓴다. */
    Path writeFixtures(Path directory) throws IOException {
        Files.createDirectories(directory);
        byte[] route = JSON.writeValueAsBytes(routeFile());
        Files.write(directory.resolve(ROUTE + ".json"), route);
        ObjectNode manifest = JSON.createObjectNode();
        manifest.put("profile", CONFIRMED_PROFILE);
        manifest.put("day", DAY);
        ObjectNode receipt = manifest.putArray("routes").addObject();
        receipt.put("route", ROUTE);
        receipt.put("cases", cases.size());
        receipt.put("observations", observations.size());
        receipt.put("prefix_vehicles", prefix.size());
        receipt.put("file", ROUTE + ".json");
        receipt.put("bytes", route.length);
        receipt.put("sha256", sha256(route));
        manifest.put("capacity_policy", CAPACITY_POLICY);
        manifest.put("label_version", LABEL_VERSION);
        Files.write(directory.resolve("manifest.json"), JSON.writeValueAsBytes(manifest));
        return directory;
    }

    /** 이 노선만 담은 비활성 번들을 쓴다. */
    Path writeBundle(Path directory) throws IOException {
        return writeBundle(directory, List.of(ROUTE));
    }

    /**
     * 비활성 번들을 쓴다. 시간 확장 자료면 시간 확장 모델을 함께 담는다.
     *
     * @param routes 번들이 담는 노선. 모두 같은 통계를 쓴다
     */
    Path writeBundle(Path directory, List<String> routes) throws IOException {
        Files.createDirectories(directory);
        Map<String, JsonNode> files = new LinkedHashMap<>();
        files.put("feature-contract.json", featureContract());
        files.put("constants.json", JSON.readTree("{\"target\":\"after\",\"classes\":[0,1,2],\"middle\":1.3}"));
        files.put("base.json", BASE.json());
        files.put("gate.json", GATE.json());
        files.put("pool.json", POOL.json());
        ObjectNode statisticsManifest = JSON.createObjectNode();
        statisticsManifest.put("schema", "alighting-statistics-v1");
        ArrayNode references = JSON.createArrayNode();
        ArrayNode names = statisticsManifest.putArray("routes");
        for (String route : routes) {
            names.add(route);
            references.addObject().put("modelRoute", route);
            files.put("statistics-" + route + ".json", statistics(route));
        }
        statisticsManifest.putArray("holiday_dates");
        files.put("statistics-manifest.json", statisticsManifest);
        files.put("route-reference.json", JSON.createObjectNode().set("routes", references));
        if (extended) {
            files.put("extended-hours.json", extendedHours(routes));
        }
        ObjectNode hashes = JSON.createObjectNode();
        for (Map.Entry<String, JsonNode> file : files.entrySet()) {
            byte[] content = JSON.writeValueAsBytes(file.getValue());
            Files.write(directory.resolve(file.getKey()), content);
            hashes.put(file.getKey(), sha256(content));
        }
        ObjectNode manifest = JSON.createObjectNode();
        manifest.put("schema", "alighting-inference-bundle-v1");
        manifest.put("model", "alighting-gate-base-and-pool");
        manifest.put("serving_ready", false);
        manifest.putArray("output_targets").add("after-alighting-estimated").add("departure-zero");
        manifest.set("files", hashes);
        Files.write(directory.resolve("manifest.json"), JSON.writeValueAsBytes(manifest));
        return directory;
    }

    /** 번들의 지문이다. 번들 {@code manifest.json}의 해시다. */
    static String bundleDigest(Path bundle) throws IOException {
        return sha256(Files.readAllBytes(bundle.resolve("manifest.json")));
    }

    private ObjectNode routeFile() {
        ObjectNode file = JSON.createObjectNode();
        file.put("capacity_policy", CAPACITY_POLICY);
        file.put("label_version", LABEL_VERSION);
        file.put("route", ROUTE);
        file.put("day", DAY);
        file.put("input_profile", CONFIRMED_PROFILE);
        file.put("source_route_id", SOURCE_ROUTE_ID);
        ObjectNode version = file.putArray("route_versions").addObject();
        version.put("id", VERSION);
        version.put("turn_sequence", STOPS);
        version.put("content_digest", "0".repeat(64));
        version.put("valid_from", "2026-10-01 00:00:00+00");
        version.putNull("valid_to");
        file.set("batches", ledger());
        file.put("day_rows_verified_against_raw_ledger", true);
        file.set("statistics", statistics);
        file.putArray("holidays");
        ArrayNode rows = file.putArray("observations");
        observations.values()
            .forEach(observation -> rows.add(lateIfChosen(row(observation), "batch_id", "received_at")));
        ArrayNode states = file.putArray("prefix");
        prefix.values().forEach(state -> states.add(state(state)));
        file.putArray("research_events");
        file.putArray("cases").addAll(cases);
        ObjectNode anchor = file.putObject("anchor");
        anchor.put("batch", FIRST_BATCH + 2);
        anchor.put("rank", FIRST_RANK - 1);
        anchor.put("time", start.minusSeconds(60).plus(CONFIRMATION_DELAY).toString());
        anchor.put("rv", VERSION);
        return file;
    }

    /** 장부에는 그날 앞의 배치도 원래 줄 수로 들어 있다. 그 배치의 줄은 자료에 일부만 있다. */
    private ArrayNode ledger() {
        ArrayNode ledger = JSON.createArrayNode();
        ledger.add(ledgerRow(FIRST_BATCH, start.minus(Duration.ofHours(10)), 7));
        ledger.add(ledgerRow(FIRST_BATCH + 1, start.minus(Duration.ofHours(9)), 5));
        ledger.add(ledgerRow(FIRST_BATCH + 2, start.minusSeconds(60), 3));
        for (int batch = 0; batch < BATCHES; batch++) {
            final long batchId = batchId(batch);
            final int rows = (int) observations.values().stream()
                .filter(observation -> observation.batchId() == batchId)
                .count();
            ledger.add(lateIfChosen(ledgerRow(batchId, responseAt(batch), rows), "id", "confirmed_at"));
        }
        return ledger;
    }

    private ObjectNode lateIfChosen(ObjectNode row, String batchField, String confirmedField) {
        Instant late = lateConfirmations.get(row.path(batchField).asLong());
        if (late != null) {
            row.put(confirmedField, late.toString());
        }
        return row;
    }

    private static ObjectNode ledgerRow(final long batchId, Instant responseAt, final int storedRows) {
        ObjectNode row = JSON.createObjectNode();
        row.put("id", batchId);
        row.put("rv", VERSION);
        row.put("confirmed_at", responseAt.plus(CONFIRMATION_DELAY).toString());
        row.put("response_at", responseAt.toString());
        row.put("stored_rows", storedRows);
        return row;
    }

    private static ObjectNode row(AlightingAwareObservation observation) {
        ObjectNode row = JSON.createObjectNode();
        row.put("id", observation.observationId());
        row.put("batch_id", observation.batchId());
        row.put("batch_rank", observation.batchRank());
        row.put("received_at", observation.receivedAt().toString());
        row.put("vehicle_id", observation.vehicleId());
        row.put("passed_stop_order", observation.passedStopOrder());
        row.put("stop_order", observation.stopOrder());
        row.put("running_state", observation.state());
        row.put("remaining_seats", observation.seats());
        row.put("crowd_level", observation.crowd());
        row.put("route_version_id", observation.routeVersionId());
        row.put("physical_received_at", observation.sourceReceivedAt().toString());
        row.put("down", observation.down());
        return row;
    }

    private static ObjectNode state(AlightingAwareVehiclePrefix state) {
        ObjectNode row = JSON.createObjectNode();
        row.put("capacity", (double) state.capacity());
        row.put("full_streak", state.fullStreak());
        row.set("previous", row(state.previous()));
        row.put("entry_at", state.entryAt().toString());
        row.put("trip_id", state.tripId());
        row.set("capacity_observation", row(state.capacityObservation()));
        return row;
    }

    private void observe(Vehicle vehicle, final int batch) {
        if (!vehicle.observedAt(batch)) {
            return;
        }
        final int passed = vehicle.passedAt(batch);
        observations.put(key(vehicle, batch), new AlightingAwareObservation(nextObservationId++, batchId(batch),
            FIRST_RANK + batch, confirmedAt(batch), vehicle.id(), passed, Math.min(passed + 1, STOPS), 1,
            vehicle.seatsAt(passed), 0, VERSION, responseAt(batch), false));
    }

    /** 그날 앞의 관측 하나다. 그날 시작보다 {@code before}만큼 앞에 수신했다. */
    private AlightingAwareObservation earlier(
        Vehicle vehicle,
        final long batchId,
        Duration before,
        final int passed,
        final int seats
    ) {
        Instant responseAt = start.minus(before);
        return new AlightingAwareObservation(nextObservationId++, batchId, FIRST_RANK - before.toHours(),
            responseAt.plus(CONFIRMATION_DELAY), vehicle.id(), passed, Math.min(passed + 1, STOPS), 1, seats, 0,
            VERSION, responseAt, false);
    }

    private ObjectNode rushCase(final int sourceBatch, final int target) {
        AlightingAwareRouteStatistics route = AlightingAwareStatisticsReader.read(statistics, Set.of());
        List<AlightingAwareObservation> history = List.copyOf(observations.values());
        List<AlightingAwarePassEvent> events = AlightingAwareHistoryCompactor.resolve(
            new AlightingAwareHistoryCompactor.History(prefix, history, List.of()), route.realStopOrders());
        AlightingAwareObservation source = observation(FOLLOWER, sourceBatch);
        AlightingAwareFeatureVector vector = new AlightingAwareFeatureSnapshot(VERSION, source, CAPACITY, route,
            history, events, prefix).forTarget(target);
        ObjectNode row = JSON.createObjectNode();
        row.put("source_id", source.observationId());
        row.put("cell", "work-morning");
        row.put("target", target);
        row.set("seat88", numbers(vector.seat88()));
        row.set("probability80", numbers(vector.probability80()));
        addSettlement(row, FOLLOWER, target);
        return row;
    }

    private ObjectNode extendedCase(Vehicle vehicle, final int sourceBatch, final int target) {
        AlightingAwareObservation source = observation(vehicle, sourceBatch);
        ZonedDateTime local = source.receivedAt().atZone(SEOUL);
        double[] inputs = {source.seats(), CAPACITY, target, target - source.passedStopOrder(),
            local.getHour() * 60 + local.getMinute(), 0};
        SeatForecastResult expected = new ExtendedAlightingPredictor(EXTENDED_SEATS.ensemble(),
            EXTENDED_FULL.ensemble()).predict(inputs);
        ObjectNode row = JSON.createObjectNode();
        row.put("source_id", source.observationId());
        row.put("target", target);
        row.put("cell", "work-day");
        row.put("settlement_cell", "work-day");
        row.set("extended_inputs", numbers(inputs));
        row.put("expected_seats", expected.expectedSeats());
        row.put("expected_full", expected.fullChance());
        return row;
    }

    /** 뒤차의 통과와 앞차의 직전 통과로 정답과 그 근거의 기대값을 적는다. */
    private void addSettlement(ObjectNode row, Vehicle vehicle, final int stop) {
        AlightingAwareObservation arrival = observation(vehicle, vehicle.arrivalBatch(stop));
        AlightingAwareObservation last = observation(vehicle, vehicle.lastBatch(stop));
        AlightingAwareObservation closure = observation(vehicle, vehicle.closureBatch(stop));
        AlightingAwareObservation previousLast = observation(LEADER, LEADER.lastBatch(stop));
        ZonedDateTime local = last.receivedAt().atZone(SEOUL);
        AlightingTimeCell cell = AlightingTimeCell.of(local, false);
        final double gap = Duration.between(previousLast.receivedAt(), last.receivedAt()).toNanos() / 60e9;
        final double after = AlightingPosterior.afterSeats(fitted(settlementParameters(cell, stop)), arrival.seats(),
            last.seats(), CAPACITY, gap, previousLast.seats() == 0, cell.settlementBin(local));
        row.put("expected_pre", arrival.observationId());
        row.put("expected_post", last.observationId());
        row.put("expected_closure", closure.observationId());
        row.put("expected_previous_post", previousLast.observationId());
        row.put("expected_capacity", CAPACITY);
        row.put("expected_after", after);
        row.put("expected_known", closure.receivedAt().toString());
    }

    private JsonNode settlementParameters(AlightingTimeCell cell, final int stop) {
        if (!cell.isRush()) {
            return parameters(0.2);
        }
        for (JsonNode candidate : statistics.path("alighting").path("stops")) {
            if (candidate.path("stop").asInt() == stop && candidate.path("band").asString().equals(cell.rushBand())) {
                return candidate;
            }
        }
        throw new IllegalStateException("정류장 계수가 없다: " + stop);
    }

    private static AlightingPosterior.Parameters fitted(JsonNode parameters) {
        double[] lambda = new double[8];
        for (int index = 0; index < lambda.length; index++) {
            lambda[index] = parameters.path("lam").get(index).asDouble();
        }
        return new AlightingPosterior.Parameters(lambda, parameters.path("p").asDouble(),
            parameters.path("kappa").asDouble(), parameters.path("r").asDouble());
    }

    private AlightingAwareObservation observation(Vehicle vehicle, final int batch) {
        return observations.get(key(vehicle, batch));
    }

    private static String key(Vehicle vehicle, final int batch) {
        return vehicle.id() + ":" + batch;
    }

    private static long batchId(final int batch) {
        return FIRST_BATCH + 10 + batch;
    }

    private Instant responseAt(final int batch) {
        return start.plusSeconds(30L * batch);
    }

    private Instant confirmedAt(final int batch) {
        return responseAt(batch).plus(CONFIRMATION_DELAY);
    }

    private static ObjectNode statistics(String route) {
        ObjectNode statistics = JSON.createObjectNode();
        statistics.put("route", route);
        statistics.put("input_profile", CONFIRMED_PROFILE);
        statistics.put("min_per_stop", 2.0);
        ObjectNode tables = statistics.putObject("tables");
        for (String table : List.of("lam", "lam_base", "net_tab", "full_tab", "dep_tab", "stop_net_base")) {
            ArrayNode rows = tables.putArray(table);
            for (int stop = 1; stop <= STOPS; stop++) {
                ObjectNode cell = rows.addObject();
                cell.putArray("key").add(stop).add("work");
                cell.put("value", 0.25 * stop + table.length() / 10.0);
            }
        }
        tables.putArray("target_band");
        ArrayNode fitted = statistics.putObject("alighting").putArray("stops");
        for (int stop = 2; stop < STOPS; stop++) {
            for (String band : List.of("morning", "evening")) {
                ObjectNode parameters = parameters(0.1 + 0.01 * stop);
                parameters.put("stop", stop);
                parameters.put("band", band);
                fitted.add(parameters);
            }
        }
        ArrayNode metadata = statistics.putArray("route_metadata");
        for (int order = 1; order <= STOPS; order++) {
            ObjectNode stop = metadata.addObject();
            stop.put("order", order);
            stop.put("id", "replay-stop-" + order);
            stop.put("name", "정류장" + order);
            stop.put("direction", "UP");
            stop.put("boardingAllowed", true);
        }
        statistics.put("events_known_through", "2026-10-21T00:00:00Z");
        statistics.put("observations_through", "2026-10-21T00:00:00Z");
        return statistics;
    }

    private static ObjectNode parameters(final double probability) {
        ObjectNode parameters = JSON.createObjectNode();
        ArrayNode rates = parameters.putArray("lam");
        for (int bin = 0; bin < 8; bin++) {
            rates.add(1.2 + 0.1 * bin);
        }
        parameters.put("p", probability);
        parameters.put("kappa", 1.0);
        parameters.put("r", 2.0);
        return parameters;
    }

    private static ObjectNode featureContract() throws IOException {
        try (InputStream input = SyntheticWorkerReplay.class.getResourceAsStream("/alighting/feature-contract.json")) {
            ObjectNode contract = (ObjectNode) JSON.readTree(input.readAllBytes());
            contract.put("input_profile", CONFIRMED_PROFILE);
            return contract;
        }
    }

    private static ObjectNode extendedHours(List<String> routes) {
        ObjectNode extendedHours = JSON.createObjectNode();
        extendedHours.put("schema", "alighting-extended-six-v1");
        ArrayNode features = extendedHours.putArray("features");
        List.of("seats", "capacity", "stop", "horizon", "minute", "off_day").forEach(features::add);
        extendedHours.set("full", EXTENDED_FULL.json());
        ObjectNode entries = extendedHours.putObject("routes");
        for (String route : routes) {
            ObjectNode entry = entries.putObject(route);
            entry.set("seats", EXTENDED_SEATS.json());
            ObjectNode settlement = entry.putObject("settlement");
            AlightingTimeCell.extendedKeys().forEach(cell -> settlement.set(cell, parameters(0.2)));
        }
        return extendedHours;
    }

    private static ArrayNode numbers(double[] values) {
        ArrayNode numbers = JSON.createArrayNode();
        for (final double value : values) {
            if (Double.isNaN(value)) {
                numbers.addNull();
                continue;
            }
            numbers.add(value);
        }
        return numbers;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /**
     * 차량 한 대의 움직임이다. 첫 배치부터 열여섯 배치 동안 관측되고 두 배치마다 한 정류장을 지난다.
     *
     * @param firstBatch 처음 관측되는 배치
     * @param startingSeats 좌석의 기준값
     * @param seatsPerStop 정류장 하나를 지날 때마다 줄어드는 좌석
     */
    private record Vehicle(String id, int firstBatch, int startingSeats, int seatsPerStop) {
        private boolean observedAt(final int batch) {
            return batch >= firstBatch && batch < firstBatch + 2 * STOPS;
        }

        private int passedAt(final int batch) {
            return 1 + (batch - firstBatch) / 2;
        }

        private int seatsAt(final int passed) {
            return startingSeats - seatsPerStop * passed;
        }

        /** 그 정류장에 닿기 전 마지막 관측의 배치다. */
        private int arrivalBatch(final int stop) {
            return firstBatch + 2 * stop - 3;
        }

        /** 그 정류장을 지난 상태의 마지막 관측의 배치다. */
        private int lastBatch(final int stop) {
            return firstBatch + 2 * stop - 1;
        }

        /** 다음 정류장을 지나 그 정류장의 통과를 닫는 관측의 배치다. */
        private int closureBatch(final int stop) {
            return firstBatch + 2 * stop;
        }
    }

    /**
     * 번들 파일과 같은 트리를 메모리에도 만든다. 클래스마다 같은 트리를 쓴다.
     *
     * @param kind 번들 파일에 적는 트리 종류
     * @param baseline 클래스별 기준값
     */
    private record Tree(String kind, int featureCount, double[] baseline, List<NumericTreeEnsemble.Node> nodes) {
        /** 입력 하나를 기준값과 견줘 두 잎으로 가르는 트리다. */
        private static Tree split(
            String kind,
            final int featureCount,
            double[] baseline,
            final int feature,
            final double threshold,
            final double atMost,
            final double above
        ) {
            return new Tree(kind, featureCount, baseline, List.of(
                new NumericTreeEnsemble.Node(feature, threshold, false, 1, 2, 0, false),
                new NumericTreeEnsemble.Node(0, 0, false, 0, 0, atMost, true),
                new NumericTreeEnsemble.Node(0, 0, false, 0, 0, above, true)));
        }

        private static Tree leaf(String kind, final int featureCount, double[] baseline) {
            return new Tree(kind, featureCount, baseline,
                List.of(new NumericTreeEnsemble.Node(0, 0, false, 0, 0, 0, true)));
        }

        private ObjectNode json() {
            ObjectNode model = JSON.createObjectNode();
            model.put("schema", "alighting-numeric-hgb-v1");
            model.put("kind", kind);
            ArrayNode classes = model.putArray("classes");
            for (int label = 0; label < classCount(); label++) {
                classes.add(label);
            }
            model.put("feature_count", featureCount);
            ArrayNode baselines = model.putArray("baseline");
            for (final double value : baseline) {
                baselines.add(value);
            }
            model.put("leaf_values_include_learning_rate", true);
            ArrayNode fields = model.putArray("node_fields");
            List.of("feature", "threshold", "missing_left", "left", "right", "value", "leaf").forEach(fields::add);
            ArrayNode iteration = model.putArray("trees").addArray();
            for (int index = 0; index < baseline.length; index++) {
                ArrayNode tree = iteration.addArray();
                nodes.forEach(node -> tree.addArray().add(node.feature()).add(node.threshold())
                    .add(node.missingLeft()).add(node.left()).add(node.right()).add(node.value()).add(node.leaf()));
            }
            return model;
        }

        /** 회귀는 클래스가 없고, 이진은 둘, 다중은 기준값 수만큼이다. */
        private int classCount() {
            if (kind.equals("regression")) {
                return 0;
            }
            return Math.max(baseline.length, 2);
        }

        private NumericTreeEnsemble ensemble() {
            return new NumericTreeEnsemble(featureCount,
                NumericTreeEnsemble.Kind.valueOf(kind.toUpperCase(Locale.ROOT)), baseline,
                List.of(Collections.nCopies(baseline.length, nodes)));
        }
    }
}
