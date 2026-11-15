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
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** 배치·차량·목표 세 단으로 나눈 입력 계산이 원본 사본과 비트 단위로 같은 결과와 같은 예외를 내는지 고정 시드 입력으로 확인한다. */
class AlightingAwareFeatureSnapshotEquivalenceTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final List<String> TABLES =
        List.of("lam", "lam_base", "net_tab", "full_tab", "dep_tab", "stop_net_base");

    @Test
    void 배치를_공유해도_모든_목표의_입력과_예외가_원본과_같다() {
        // given
        Random random = new Random(434L);
        Tally tally = new Tally();

        // when
        for (int scenario = 0; scenario < 60; scenario++) {
            Route route = route(random, scenario);
            List<List<AlightingAwareObservation>> batches = simulate(random, route);
            for (int pick = 0; pick < 6; pick++) {
                final int sourceBatch = batches.size() / 3 + random.nextInt(batches.size() * 2 / 3);
                compareBatch(random, route, batches, sourceBatch, tally);
            }
        }

        // then
        assertSoftly(softly -> {
            softly.assertThat(tally.mismatches).isEmpty();
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
    void 원본이_거부하는_입력은_같은_시점에_같은_예외로_거부한다() {
        // given
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
        compareOne(route, source, 0, history, List.of(), Map.of(), List.of(5, 6), tally, failures);
        compareOne(route, source, 71, history, List.of(), Map.of(), List.of(5), tally, failures);
        compareOne(route, source, 40, history, List.of(), Map.of(),
            List.of(4, 17, route.statistics.largestStopOrder() + 1), tally, failures);
        compareOne(route, unknownSeats, 40, history, List.of(), Map.of(), List.of(5), tally, failures);
        compareOne(route, anonymous, 40, history, List.of(), Map.of(), List.of(5), tally, failures);
        compareOne(route, source, 40, List.of(earlier), List.of(), Map.of(), List.of(5), tally, failures);

        // then
        assertSoftly(softly -> {
            softly.assertThat(tally.mismatches).isEmpty();
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
        List<Pair> snapshots = new ArrayList<>();
        for (AlightingAwareObservation source : batches.get(sourceBatch)) {
            final int capacity = capacity(random);
            Outcome legacy = outcome(() -> new LegacyAlightingAwareFeatureSnapshot(route.routeVersionId, source,
                capacity, route.statistics, history, events, prefix));
            Outcome current = outcome(() -> new AlightingAwareFeatureSnapshot(route.routeVersionId, source, capacity,
                context));
            compare("constructor " + source.observationId(), legacy, current, tally);
            if (legacy.error() != null) {
                tally.failures.merge(String.valueOf(legacy.error().getMessage()), 1, Integer::sum);
            }
            if (legacy.value() != null && current.value() != null) {
                snapshots.add(new Pair((LegacyAlightingAwareFeatureSnapshot) legacy.value(),
                    (AlightingAwareFeatureSnapshot) current.value(), random.nextBoolean()));
            }
        }
        tally.batches++;
        if (snapshots.size() > 1) {
            tally.batchesWithSeveralVehicles++;
        }
        List<int[]> calls = new ArrayList<>();
        for (int vehicle = 0; vehicle < snapshots.size(); vehicle++) {
            for (int target = 0; target <= route.statistics.largestStopOrder() + 1; target++) {
                calls.add(new int[] { vehicle, target });
                if (random.nextInt(5) == 0) {
                    calls.add(new int[] { vehicle, target });
                }
            }
        }
        Collections.shuffle(calls, random);
        for (Pair pair : snapshots) {
            if (pair.provenanceFirst()) {
                compareAccessors(pair, tally);
            }
        }
        Set<Integer> reached = new HashSet<>();
        for (int[] call : calls) {
            Pair pair = snapshots.get(call[0]);
            if (compareTarget(pair, call[1], tally)) {
                reached.add(call[0]);
            }
        }
        tally.snapshots += snapshots.size();
        tally.snapshotsWithVector += reached.size();
        for (Pair pair : snapshots) {
            compareAccessors(pair, tally);
        }
    }

    private static void compareOne(
        Route route,
        AlightingAwareObservation source,
        final int capacity,
        List<AlightingAwareObservation> history,
        List<AlightingAwarePassEvent> events,
        Map<String, AlightingAwareVehiclePrefix> prefix,
        List<Integer> targets,
        Tally tally,
        List<String> failures
    ) {
        Outcome legacy = outcome(() -> new LegacyAlightingAwareFeatureSnapshot(route.routeVersionId, source, capacity,
            route.statistics, history, events, prefix));
        Outcome current = outcome(() -> new AlightingAwareFeatureSnapshot(route.routeVersionId, source, capacity,
            route.statistics, history, events, prefix));
        compare("constructor", legacy, current, tally);
        if (legacy.error() != null) {
            failures.add(legacy.error().getMessage());
            return;
        }
        Pair pair = new Pair((LegacyAlightingAwareFeatureSnapshot) legacy.value(),
            (AlightingAwareFeatureSnapshot) current.value(), false);
        for (final int target : targets) {
            Outcome expected = outcome(() -> pair.legacy().forTarget(target));
            compareTarget(pair, target, tally);
            if (expected.error() != null) {
                failures.add(expected.error().getMessage());
            }
        }
        compareAccessors(pair, tally);
    }

    private static boolean compareTarget(Pair pair, final int target, Tally tally) {
        Outcome legacy = outcome(() -> pair.legacy().forTarget(target));
        Outcome current = outcome(() -> pair.current().forTarget(target));
        tally.attempts++;
        countTargetOutcome(legacy, tally);
        compare("target " + pair.legacy().vehicleObservationId() + "->" + target, legacy, current, tally);
        return legacy.error() == null;
    }

    private static void countTargetOutcome(Outcome legacy, Tally tally) {
        if (legacy.error() != null) {
            tally.failures.merge(String.valueOf(legacy.error().getMessage()), 1, Integer::sum);
            return;
        }
        tally.vectors++;
    }

    private static void compareAccessors(Pair pair, Tally tally) {
        String name = "accessors " + pair.legacy().vehicleObservationId();
        compare(name + " provenance", outcome(() -> pair.legacy().provenance().tripStartObservationId()),
            outcome(() -> pair.current().provenance().tripStartObservationId()), tally);
        compare(name + " observation", outcome(() -> pair.legacy().observation()),
            outcome(() -> pair.current().observation()), tally);
        compare(name + " capacity", outcome(() -> pair.legacy().capacity()),
            outcome(() -> pair.current().capacity()), tally);
        compare(name + " id", outcome(() -> pair.legacy().vehicleObservationId()),
            outcome(() -> pair.current().vehicleObservationId()), tally);
        compare(name + " source", outcome(() -> pair.legacy().source()), outcome(() -> pair.current().source()),
            tally);
    }

    private static void compare(
        String name,
        Outcome legacy,
        Outcome current,
        Tally tally
    ) {
        if (!sameOutcome(legacy, current)) {
            tally.mismatches.add(name + ": " + legacy + " / " + current);
        }
    }

    private static boolean sameOutcome(Outcome legacy, Outcome current) {
        if (legacy.error() != null || current.error() != null) {
            if (legacy.error() == null || current.error() == null) {
                return false;
            }
            if (legacy.error().getClass() != current.error().getClass()) {
                return false;
            }
            return legacy.error() instanceof NullPointerException
                || Objects.equals(legacy.error().getMessage(), current.error().getMessage());
        }
        if (legacy.value() instanceof LegacyAlightingAwareFeatureSnapshot
            || current.value() instanceof AlightingAwareFeatureSnapshot) {
            return true;
        }
        if (legacy.value() instanceof AlightingAwareFeatureVector expected
            && current.value() instanceof AlightingAwareFeatureVector actual) {
            return Arrays.equals(expected.seat88(), actual.seat88())
                && Arrays.equals(expected.probability80(), actual.probability80());
        }
        return Objects.equals(legacy.value(), current.value());
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

    private record Pair(
        LegacyAlightingAwareFeatureSnapshot legacy,
        AlightingAwareFeatureSnapshot current,
        boolean provenanceFirst
    ) {
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
