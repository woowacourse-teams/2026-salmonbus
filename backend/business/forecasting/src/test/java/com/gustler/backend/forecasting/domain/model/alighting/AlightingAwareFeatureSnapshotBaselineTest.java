package com.gustler.backend.forecasting.domain.model.alighting;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * 배치·차량·목표 세 단으로 나눈 입력 계산이 고정 시드 입력에서 기록해 둔 기대값({@link FeatureSnapshotBaseline})과 비트 단위로 같은
 * 결과와 같은 예외를 내는지 확인한다. 기대값은 다시 만들 수 없으므로 노선·관측을 만드는 흐름과 난수를 뽑는 순서를 바꾸지 않는다.
 */
class AlightingAwareFeatureSnapshotBaselineTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final List<String> TABLES =
        List.of("lam", "lam_base", "net_tab", "full_tab", "dep_tab", "stop_net_base");

    @Test
    void 배치를_공유해도_모든_목표의_입력과_예외가_기준_기록과_같다() {
        // given
        Random random = new Random(434L);
        FeatureSnapshotBaseline baseline = FeatureSnapshotBaseline.read("feature-snapshot/shared-batches-434");
        Tally tally = new Tally();

        // when
        for (int scenario = 0; scenario < 60; scenario++) {
            Route route = route(random, scenario);
            List<List<AlightingAwareObservation>> batches = simulate(random, route);
            for (int pick = 0; pick < 6; pick++) {
                final int sourceBatch = batches.size() / 3 + random.nextInt(batches.size() * 2 / 3);
                compareBatch(random, route, batches, sourceBatch, baseline, scenario + "." + pick, tally);
            }
        }

        // then
        assertSoftly(softly -> {
            softly.assertThat(tally.mismatches).isEmpty();
            softly.assertThat(baseline.unread()).isEmpty();
            softly.assertThat(tally.batches).isGreaterThanOrEqualTo(300);
            softly.assertThat(tally.vectors).isGreaterThanOrEqualTo(3000);
            softly.assertThat(tally.snapshotsWithVector).isGreaterThan(tally.snapshots * 3 / 4);
            softly.assertThat(tally.batchesWithSeveralVehicles).isGreaterThan(tally.batches / 2);
            softly.assertThat(tally.eventsSeen).isGreaterThan(0);
            softly.assertThat(tally.prefixedBatches).isGreaterThan(0);
            softly.assertThat(tally.failures.keySet()).contains("Unsupported AlightingAware target",
                "Invalid vehicle capacity snapshot", "Unknown current seats",
                "Anonymous observation is not a forecast source");
        });
    }

    @Test
    void 거부할_입력은_기준_기록과_같은_시점에_같은_예외로_거부한다() {
        // given
        FeatureSnapshotBaseline baseline = FeatureSnapshotBaseline.read("feature-snapshot/rejections");
        Route route = fixedRoute();
        Instant now = Instant.parse("2026-10-15T23:30:00Z");
        AlightingAwareObservation earlier = row(route, 1, 0, now.minusSeconds(30), "bus", 3, 20);
        AlightingAwareObservation source = row(route, 2, 1, now, "bus", 4, 18);
        AlightingAwareObservation unknownSeats = row(route, 3, 1, now, "other", 4, null);
        AlightingAwareObservation anonymous = row(route, 4, 1, now, AlightingAwareObservation.ANONYMOUS_GROUP, 4, 10);
        List<AlightingAwareObservation> history = List.of(earlier, source, unknownSeats, anonymous);
        List<String> failures = new ArrayList<>();
        Tally tally = new Tally();

        // when
        compareOne(baseline, "capacity-0", route, source, 0, history, List.of(5, 6), tally, failures);
        compareOne(baseline, "capacity-71", route, source, 71, history, List.of(5), tally, failures);
        compareOne(baseline, "unsupported-targets", route, source, 40, history,
            List.of(4, 17, route.statistics.largestStopOrder() + 1), tally, failures);
        compareOne(baseline, "unknown-seats", route, unknownSeats, 40, history, List.of(5), tally, failures);
        compareOne(baseline, "anonymous", route, anonymous, 40, history, List.of(5), tally, failures);
        compareOne(baseline, "missing-source", route, source, 40, List.of(earlier), List.of(5), tally, failures);

        // then
        assertSoftly(softly -> {
            softly.assertThat(tally.mismatches).isEmpty();
            softly.assertThat(baseline.unread()).isEmpty();
            softly.assertThat(failures).contains("Invalid vehicle capacity snapshot",
                "Unsupported AlightingAware target", "Unknown current seats",
                "Anonymous observation is not a forecast source",
                "Source observation missing from AlightingAware history");
        });
    }

    private static void compareBatch(
        Random random,
        Route route,
        List<List<AlightingAwareObservation>> batches,
        final int sourceBatch,
        FeatureSnapshotBaseline baseline,
        String name,
        Tally tally
    ) {
        Instant asOf = batches.get(sourceBatch).getFirst().receivedAt();
        List<AlightingAwareObservation> past = new ArrayList<>();
        List<AlightingAwareObservation> future = new ArrayList<>();
        for (int index = 0; index < batches.size(); index++) {
            if (index <= sourceBatch) {
                past.addAll(batches.get(index));
                continue;
            }
            if (index <= sourceBatch + 3) {
                future.addAll(batches.get(index));
            }
        }
        AlightingAwareHistoryCompactor.History retained =
            new AlightingAwareHistoryCompactor.History(Map.of(), past, List.of());
        if (random.nextBoolean()) {
            retained = AlightingAwareHistoryCompactor.compact(new AlightingAwareHistoryCompactor.History(Map.of(), past,
                AlightingAwareHistoryCompactor.resolve(retained, route.statistics.realStopOrders())),
                asOf.plusSeconds(3600 + random.nextInt(3600)));
            if (!retained.prefix().isEmpty()) {
                tally.prefixedBatches++;
            }
        }
        Map<String, AlightingAwareVehiclePrefix> prefix = retained.prefix();
        List<AlightingAwareObservation> history = new ArrayList<>(retained.observations());
        history.addAll(future);
        List<AlightingAwarePassEvent> pastEvents = retained.events();
        List<AlightingAwarePassEvent> events = AlightingAwareHistoryCompactor.resolve(
            new AlightingAwareHistoryCompactor.History(prefix, history, pastEvents), route.statistics.realStopOrders());
        tally.eventsSeen += events.size();

        AlightingAwareBatchContext context = new AlightingAwareBatchContext(route.statistics, history, events, prefix);
        List<Integer> targets = IntStream.rangeClosed(0, route.statistics.largestStopOrder() + 1).boxed().toList();
        List<Vehicle> vehicles = new ArrayList<>();
        for (AlightingAwareObservation source : batches.get(sourceBatch)) {
            final int capacity = capacity(random);
            String key = name + "." + source.observationId();
            FeatureSnapshotBaseline.Expected expected = baseline.vehicle(key);
            Outcome current = outcome(() -> new AlightingAwareFeatureSnapshot(route.routeVersionId, source, capacity,
                context));
            compare("constructor " + key, expected.rejection(), current, tally);
            if (expected.rejection() != null) {
                tally.failures.merge(String.valueOf(expected.rejection().message()), 1, Integer::sum);
            }
            if (expected.rejection() == null && current.value() != null) {
                vehicles.add(new Vehicle(key, expected, (AlightingAwareFeatureSnapshot) current.value(), targets,
                    random.nextBoolean()));
            }
        }
        tally.batches++;
        if (vehicles.size() > 1) {
            tally.batchesWithSeveralVehicles++;
        }
        List<int[]> calls = new ArrayList<>();
        for (int vehicle = 0; vehicle < vehicles.size(); vehicle++) {
            for (int target = 0; target <= route.statistics.largestStopOrder() + 1; target++) {
                calls.add(new int[] { vehicle, target });
                if (random.nextInt(5) == 0) {
                    calls.add(new int[] { vehicle, target });
                }
            }
        }
        Collections.shuffle(calls, random);
        for (Vehicle vehicle : vehicles) {
            if (vehicle.provenanceFirst) {
                compareAccessors(vehicle, tally);
            }
        }
        Set<Integer> reached = new HashSet<>();
        for (int[] call : calls) {
            Vehicle vehicle = vehicles.get(call[0]);
            if (compareTarget(vehicle, call[1], tally)) {
                reached.add(call[0]);
            }
        }
        tally.snapshots += vehicles.size();
        tally.snapshotsWithVector += reached.size();
        for (Vehicle vehicle : vehicles) {
            compareAccessors(vehicle, tally);
            compareDigest(vehicle, tally);
        }
    }

    private static void compareOne(
        FeatureSnapshotBaseline baseline,
        String name,
        Route route,
        AlightingAwareObservation source,
        final int capacity,
        List<AlightingAwareObservation> history,
        List<Integer> targets,
        Tally tally,
        List<String> failures
    ) {
        FeatureSnapshotBaseline.Expected expected = baseline.vehicle(name);
        Outcome current = outcome(() -> new AlightingAwareFeatureSnapshot(route.routeVersionId, source, capacity,
            route.statistics, history, List.of(), Map.of()));
        compare("constructor " + name, expected.rejection(), current, tally);
        if (expected.rejection() != null) {
            failures.add(expected.rejection().message());
            return;
        }
        if (current.value() == null) {
            return;
        }
        Vehicle vehicle = new Vehicle(name, expected, (AlightingAwareFeatureSnapshot) current.value(), targets, false);
        for (final int target : targets) {
            compareTarget(vehicle, target, tally);
            FeatureSnapshotBaseline.Rejection rejection = vehicle.expectedRejection(target);
            if (rejection != null) {
                failures.add(rejection.message());
            }
        }
        compareAccessors(vehicle, tally);
        compareDigest(vehicle, tally);
    }

    private static boolean compareTarget(Vehicle vehicle, final int target, Tally tally) {
        FeatureSnapshotBaseline.Rejection expected = vehicle.expectedRejection(target);
        Outcome current = outcome(() -> vehicle.snapshot.forTarget(target));
        tally.attempts++;
        countTargetOutcome(expected, tally);
        compare("target " + vehicle.key + "->" + target, expected, current, tally);
        if (expected == null && current.value() instanceof AlightingAwareFeatureVector vector) {
            vehicle.remember(target, vector, tally);
        }
        return expected == null;
    }

    private static void countTargetOutcome(FeatureSnapshotBaseline.Rejection expected, Tally tally) {
        if (expected != null) {
            tally.failures.merge(String.valueOf(expected.message()), 1, Integer::sum);
            return;
        }
        tally.vectors++;
    }

    private static void compareAccessors(Vehicle vehicle, Tally tally) {
        Outcome tripStart = outcome(() -> vehicle.snapshot.provenance().tripStartObservationId());
        if (!sameTripStart(vehicle.expected, tripStart)) {
            tally.mismatches.add("accessors " + vehicle.key + " provenance: " + vehicle.expected.tripStart() + " "
                + vehicle.expected.tripStartRejection() + " / " + tripStart);
        }
        vehicle.rememberValues(values(vehicle.snapshot), tally);
    }

    private static boolean sameTripStart(FeatureSnapshotBaseline.Expected expected, Outcome current) {
        if (expected.tripStartRejection() != null) {
            return sameOutcome(expected.tripStartRejection(), current);
        }
        return current.error() == null && expected.tripStart().equals(current.value());
    }

    /** 목표와 무관한 값(관측 값, 정원, 기준 관측 번호, 기준 관측)을 글로 적는다. 값 대신 예외가 나면 그 예외를 적는다. */
    private static String values(AlightingAwareFeatureSnapshot snapshot) {
        return String.join("\n",
            "observation=" + text(outcome(() -> FeatureSnapshotBaseline.text(snapshot.observation()))),
            "capacity=" + text(outcome(() -> snapshot.capacity())),
            "id=" + text(outcome(() -> snapshot.vehicleObservationId())),
            "source=" + text(outcome(() -> FeatureSnapshotBaseline.text(snapshot.source()))));
    }

    private static String text(Outcome outcome) {
        if (outcome.error() != null) {
            return "!" + outcome.error().getClass().getName();
        }
        return String.valueOf(outcome.value());
    }

    private static void compareDigest(Vehicle vehicle, Tally tally) {
        String actual = vehicle.digest();
        if (!vehicle.expected.digest().equals(actual)) {
            tally.mismatches.add("values " + vehicle.key + ": " + vehicle.expected.digest() + " / " + actual);
        }
    }

    /** 기대값이 거부면 같은 예외를 냈는지, 아니면 예외 없이 값을 냈는지 본다. */
    private static void compare(
        String name,
        FeatureSnapshotBaseline.Rejection expected,
        Outcome current,
        Tally tally
    ) {
        if (!sameOutcome(expected, current)) {
            tally.mismatches.add(name + ": " + describe(expected) + " / " + current);
        }
    }

    private static String describe(FeatureSnapshotBaseline.Rejection expected) {
        if (expected == null) {
            return "예외 없음";
        }
        return expected.toString();
    }

    private static boolean sameOutcome(FeatureSnapshotBaseline.Rejection expected, Outcome current) {
        if (expected == null) {
            return current.error() == null;
        }
        return current.error() != null && expected.matches(current.error());
    }

    private static boolean sameVector(AlightingAwareFeatureVector first, AlightingAwareFeatureVector second) {
        return Arrays.equals(first.seat88(), second.seat88())
            && Arrays.equals(first.probability80(), second.probability80());
    }

    private static Outcome outcome(Supplier<Object> call) {
        try {
            return new Outcome(call.get(), null);
        } catch (RuntimeException error) {
            return new Outcome(null, error);
        }
    }

    private static int capacity(Random random) {
        if (random.nextInt(12) == 0) {
            return List.of(0, -3, 71, 90).get(random.nextInt(4));
        }
        return 35 + random.nextInt(35);
    }

    private static Route route(Random random, final int scenario) {
        final boolean confirmed = scenario % 2 == 1;
        final int stopCount = 14 + random.nextInt(20);
        final int turn = stopCount / 2 + random.nextInt(3) - 1;
        List<AlightingAwareRouteStatistics.Stop> stops = new ArrayList<>();
        for (int order = 1; order <= stopCount; order++) {
            final boolean via = isRandomViaStop(random, order, stopCount);
            final boolean boarding = !via && random.nextInt(10) != 0;
            stops.add(new AlightingAwareRouteStatistics.Stop(order, "s" + order, stopName(order, via), order > turn,
                boarding));
        }
        Map<String, Map<String, Double>> tables = new HashMap<>();
        for (String table : TABLES) {
            Map<String, Double> cells = new HashMap<>();
            for (int order = 1; order <= stopCount; order++) {
                for (String day : List.of("work", "off")) {
                    if (table.endsWith("_base")) {
                        if (random.nextInt(5) != 0) {
                            cells.put(order + "|" + day, value(random, table));
                        }
                        continue;
                    }
                    for (int slot = 0; slot < 48; slot++) {
                        if (random.nextInt(10) < 7) {
                            cells.put(order + "|" + day + "|" + slot, value(random, table));
                        }
                    }
                }
            }
            tables.put(table, cells);
        }
        Map<String, List<Double>> bands = new HashMap<>();
        for (int order = 1; order <= stopCount; order++) {
            for (String band : List.of("morning", "evening", "other")) {
                if (random.nextInt(10) < 7) {
                    bands.put(order + "|" + band,
                        List.of(random.nextDouble() * 20, random.nextDouble() * 10, random.nextDouble()));
                }
            }
        }
        LocalDate day = LocalDate.of(2026, 10, 1).plusDays(random.nextInt(60));
        Instant start = day.atTime(5 + random.nextInt(17), random.nextInt(60)).atZone(SEOUL).toInstant();
        Set<LocalDate> holidays = holidays(random, day);
        AlightingAwareRouteStatistics statistics = new AlightingAwareRouteStatistics("r" + scenario, stops,
            1.5 + random.nextDouble() * 2, tables, bands, holidays, confirmed);
        return new Route(statistics, routeVersionId(confirmed, scenario), start, turn, confirmed, new long[] { 1 });
    }

    private static boolean isRandomViaStop(Random random, final int order, final int stopCount) {
        return order > 2 && order < stopCount - 1 && random.nextInt(7) == 0;
    }

    private static String stopName(final int order, final boolean via) {
        if (via) {
            return "경유" + order;
        }
        return "정류장" + order;
    }

    private static Set<LocalDate> holidays(Random random, LocalDate day) {
        if (random.nextInt(4) == 0) {
            return Set.of(day);
        }
        return Set.of();
    }

    private static long routeVersionId(final boolean confirmed, final int scenario) {
        if (confirmed) {
            return 7 + scenario;
        }
        return 1;
    }

    private static Route fixedRoute() {
        List<AlightingAwareRouteStatistics.Stop> stops = new ArrayList<>();
        for (int order = 1; order <= 20; order++) {
            stops.add(new AlightingAwareRouteStatistics.Stop(order, "s" + order, fixedStopName(order), order > 10,
                order != 6));
        }
        Map<String, Map<String, Double>> tables = new HashMap<>();
        for (String table : TABLES) {
            tables.put(table, Map.of());
        }
        AlightingAwareRouteStatistics statistics =
            new AlightingAwareRouteStatistics("fixed", stops, 2, tables, Map.of(), Set.of());
        return new Route(statistics, 1, Instant.parse("2026-10-15T23:00:00Z"), 10, false, new long[] { 1 });
    }

    private static String fixedStopName(final int order) {
        if (order == 6) {
            return "경유";
        }
        return "정류장";
    }

    private static double value(Random random, String table) {
        return switch (table) {
            case "lam", "lam_base" -> random.nextDouble() * 2;
            case "full_tab" -> random.nextDouble();
            case "dep_tab" -> random.nextDouble() * 40;
            default -> random.nextDouble() * 6 - 3;
        };
    }

    private static List<List<AlightingAwareObservation>> simulate(Random random, Route route) {
        final int vehicles = 2 + random.nextInt(7);
        final int largest = route.statistics.largestStopOrder();
        int[] passed = new int[vehicles];
        Integer[] seats = new Integer[vehicles];
        int[] pause = new int[vehicles];
        for (int vehicle = 0; vehicle < vehicles; vehicle++) {
            passed[vehicle] = 1 + random.nextInt(largest - 1);
            seats[vehicle] = random.nextInt(45);
        }
        List<List<AlightingAwareObservation>> batches = new ArrayList<>();
        Instant time = route.start;
        final int batchCount = 60 + random.nextInt(120);
        for (int batch = 0; batch < batchCount; batch++) {
            time = time.plusSeconds(15 + random.nextInt(30));
            List<AlightingAwareObservation> rows = new ArrayList<>();
            for (int vehicle = 0; vehicle < vehicles; vehicle++) {
                if (pause[vehicle] > 0) {
                    pause[vehicle]--;
                    continue;
                }
                if (random.nextInt(40) == 0) {
                    pause[vehicle] = 2 + random.nextInt(6);
                    continue;
                }
                advance(random, passed, seats, vehicle, largest);
                Instant at = observedAt(random, time);
                rows.add(row(route, route.nextId(), batch, at, "v" + vehicle, passed[vehicle], seats[vehicle]));
            }
            if (random.nextInt(15) == 0) {
                rows.add(row(route, route.nextId(), batch, time, AlightingAwareObservation.ANONYMOUS_GROUP,
                    1 + random.nextInt(largest), random.nextInt(30)));
            }
            if (rows.isEmpty()) {
                rows.add(row(route, route.nextId(), batch, time, "v0", passed[0], seats[0]));
            }
            batches.add(rows);
        }
        return batches;
    }

    private static Instant observedAt(Random random, Instant time) {
        if (random.nextInt(25) == 0) {
            return time.plusSeconds(1);
        }
        return time;
    }

    private static void advance(
        Random random,
        int[] passed,
        Integer[] seats,
        final int vehicle,
        final int largest
    ) {
        final int move = random.nextInt(10);
        passed[vehicle] += stopsMoved(move);
        if (passed[vehicle] >= largest || random.nextInt(150) == 0) {
            passed[vehicle] = 1;
        }
        final int known = knownSeats(seats[vehicle]);
        final int next = nextSeats(random, known);
        seats[vehicle] = reportedSeats(random, next);
    }

    private static int stopsMoved(final int move) {
        if (move < 4) {
            return 1;
        }
        if (move == 4) {
            return 2;
        }
        return 0;
    }

    private static int knownSeats(Integer seats) {
        if (seats == null) {
            return 20;
        }
        return seats;
    }

    /** 좌석을 바꾸는 경우에만 변화량 난수를 하나 더 뽑는다. */
    private static int nextSeats(Random random, final int known) {
        if (random.nextInt(8) == 0) {
            return 0;
        }
        return Math.max(0, Math.min(45, known + random.nextInt(11) - 5));
    }

    private static Integer reportedSeats(Random random, final int next) {
        if (random.nextInt(14) == 0) {
            return null;
        }
        return next;
    }

    private static AlightingAwareObservation row(
        Route route,
        final long id,
        final long rank,
        Instant at,
        String vehicle,
        final int passed,
        Integer seats
    ) {
        final int state = (int) (id % 3);
        if (!route.confirmed) {
            return new AlightingAwareObservation(id, 1000 + rank, rank, at, vehicle, passed, passed + 1, state, seats,
                (int) (id % 5));
        }
        Instant physical = physicalAt(id, at);
        return new AlightingAwareObservation(id, 1000 + rank, rank, at, vehicle, passed, passed + 1, state, seats,
            (int) (id % 5), route.routeVersionId, physical, passed > route.turn);
    }

    private static Instant physicalAt(final long id, Instant at) {
        if (id % 31 == 0) {
            return at.minusSeconds(120);
        }
        return at.minusSeconds(id % 4);
    }

    private record Route(
        AlightingAwareRouteStatistics statistics,
        long routeVersionId,
        Instant start,
        int turn,
        boolean confirmed,
        long[] ids
    ) {
        private long nextId() {
            return ids[0]++;
        }
    }

    /** 기대값과 견주는 차량 하나. 목표마다 처음 받은 입력 벡터와 처음 읽은 값을 기억해 두었다가 지문을 만든다. */
    private static final class Vehicle {
        private final String key;
        private final FeatureSnapshotBaseline.Expected expected;
        private final AlightingAwareFeatureSnapshot snapshot;
        private final List<Integer> targets;
        private final boolean provenanceFirst;
        private final Map<Integer, AlightingAwareFeatureVector> vectors = new HashMap<>();
        private String values;

        private Vehicle(
            String key,
            FeatureSnapshotBaseline.Expected expected,
            AlightingAwareFeatureSnapshot snapshot,
            List<Integer> targets,
            final boolean provenanceFirst
        ) {
            this.key = key;
            this.expected = expected;
            this.snapshot = snapshot;
            this.targets = targets;
            this.provenanceFirst = provenanceFirst;
        }

        /** 그 목표의 기대 거부 사유. 입력 벡터를 내야 하는 목표면 비어 있다. */
        private FeatureSnapshotBaseline.Rejection expectedRejection(final int target) {
            return expected.targets().get(targets.indexOf(target));
        }

        /** 같은 목표를 다시 불러 받은 입력 벡터가 처음과 다르면 불일치로 적는다. */
        private void remember(final int target, AlightingAwareFeatureVector vector, Tally tally) {
            AlightingAwareFeatureVector first = vectors.putIfAbsent(target, vector);
            if (first != null && !sameVector(first, vector)) {
                tally.mismatches.add("repeated target " + key + "->" + target);
            }
        }

        /** 다시 읽은 값이 처음과 다르면 불일치로 적는다. */
        private void rememberValues(String read, Tally tally) {
            if (values == null) {
                values = read;
                return;
            }
            if (!values.equals(read)) {
                tally.mismatches.add("repeated accessors " + key + ": " + values + " / " + read);
            }
        }

        private String digest() {
            List<AlightingAwareFeatureVector> ordered = new ArrayList<>();
            for (final int target : targets) {
                ordered.add(vectors.get(target));
            }
            return FeatureSnapshotBaseline.digest(values, ordered);
        }
    }

    private record Outcome(Object value, RuntimeException error) {
    }

    private static final class Tally {
        private final List<String> mismatches = new ArrayList<>();
        private final Map<String, Integer> failures = new HashMap<>();
        private int batches;
        private int batchesWithSeveralVehicles;
        private int prefixedBatches;
        private long eventsSeen;
        private long attempts;
        private long vectors;
        private int snapshots;
        private int snapshotsWithVector;
    }
}
