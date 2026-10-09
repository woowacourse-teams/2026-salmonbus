package com.gustler.localgbis;

import java.nio.file.Path;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReplayObservationStatesTest {
    @Test
    void everyRouteShowsZeroOneTwoAndThreeApproachingVehiclesOverTime() throws Exception {
        var data = ReplayEngine.readRoutes(Path.of(System.getProperty("routes.file")));
        for (var route : data.routes()) {
            var time = new AtomicLong();
            var engine = engine(data, time);
            var counts = new HashSet<Integer>();
            for (int second = 0; second <= 360; second += 10) {
                time.set(second * 1_000_000_000L);
                var buses = vehicles(engine.response("location", "normal", route.routeId()));
                for (var stop : route.stops()) {
                    if (stop.boardingAllowed()) {
                        final long approaching = buses.stream().filter(bus -> {
                            final int passed = (Integer) bus.get("stationSeq") - (bus.get("stateCd").equals(1) ? 1 : 0);
                            final int horizon = stop.sequence() - passed;
                            return horizon >= 1 && horizon <= 12;
                        }).count();
                        counts.add((int) Math.min(approaching, 3));
                    }
                }
            }
            assertTrue(counts.containsAll(List.of(0, 1, 2, 3)), route.displayName() + ": " + counts);
        }
    }

    @Test
    void bunchedProvidesMoreThanThreeCandidatesForApiTruncation() throws Exception {
        var data = ReplayEngine.readRoutes(Path.of(System.getProperty("routes.file")));
        for (var route : data.routes()) {
            var buses = vehicles(engine(data, new AtomicLong()).response("location", "bunched", route.routeId()));
            assertTrue(route.stops().stream().filter(ReplayEngine.Stop::boardingAllowed).anyMatch(stop ->
                buses.stream().filter(bus -> {
                    final int passed = (Integer) bus.get("stationSeq") - (bus.get("stateCd").equals(1) ? 1 : 0);
                    return stop.sequence() - passed >= 1 && stop.sequence() - passed <= 12;
                }).count() > 3), route.displayName());
        }
    }

    @Test
    void mixedIncludesEverySeatKindAndNormalIncludesEveryPhase() throws Exception {
        var data = ReplayEngine.readRoutes(Path.of(System.getProperty("routes.file")));
        var engine = engine(data, new AtomicLong());
        var buses = vehicles(engine.response("location", "mixed", "204000070"));
        for (final int seats : List.of(0, 3, 16, 32, -1)) {
            assertTrue(buses.stream().anyMatch(bus -> Integer.valueOf(seats).equals(bus.get("remainSeatCnt"))));
        }
        assertTrue(buses.stream().anyMatch(bus -> !bus.containsKey("remainSeatCnt")));
        assertEquals(java.util.Set.of(0, 1, 2), new HashSet<>(buses.stream().map(bus -> bus.get("stateCd")).toList()));
        for (var entry : Map.of("arriving", 1, "departed", 2, "in-transit", 0, "unreadable", 99).entrySet()) {
            assertTrue(vehicles(engine.response("location", entry.getKey(), "204000070")).stream()
                .allMatch(bus -> entry.getValue().equals(bus.get("stateCd"))));
        }
        assertTrue(vehicles(engine.response("location", "anonymous", "204000070")).stream()
            .allMatch(bus -> !bus.containsKey("vehId") && !bus.containsKey("plateNo")));
        assertTrue(vehicles(engine.response("location", "missing-seat", "204000070")).stream()
            .allMatch(bus -> !bus.containsKey("remainSeatCnt")));
    }

    @Test
    void singleAndDirectionModesPreservePositionsAndStopIdentity() throws Exception {
        var data = ReplayEngine.readRoutes(Path.of(System.getProperty("routes.file")));
        var engine = engine(data, new AtomicLong());
        assertEquals(2, vehicles(engine.response("location", "single", "204000070")).size());
        for (var entry : Map.of("up-only", "-U", "down-only", "-D").entrySet()) {
            assertTrue(vehicles(engine.response("location", entry.getKey(), "204000070")).stream()
                .allMatch(bus -> bus.get("plateNo").toString().endsWith(entry.getValue())));
            for (var route : data.routes()) {
                String direction = entry.getKey().equals("up-only") ? "UP" : "DOWN";
                assertTrue(vehicles(engine.response("location", entry.getKey(), route.routeId())).stream()
                    .allMatch(bus -> direction.equals(route.stops().get((Integer) bus.get("stationSeq") - 1).direction())),
                    route.displayName() + ": " + entry.getKey());
            }
        }
        for (String mode : ReplayEngine.MODES) {
            assertEquals(200, engine.response("route-info", mode, "204000070").status());
        }
        var invalid = engine.response("location", "invalid-response", "204000070");
        assertEquals(200, invalid.status());
        assertFalse(((Map<?, ?>) invalid.body().get("response")).containsKey("msgBody"));
    }

    private static ReplayEngine engine(ReplayEngine.Dataset data, AtomicLong time) {
        return new ReplayEngine(data, new ReplayEngine.Configuration("local-replay-v1", 30, 60, 6),
            Clock.systemUTC(), time::get, "testrun1");
    }

    @Test
    void layoutChangesStartNewVehicleHistoriesWithoutResettingOtherRoutes() throws Exception {
        var data = ReplayEngine.readRoutes(Path.of(System.getProperty("routes.file")));
        var engine = engine(data, new AtomicLong());
        var first = vehicles(engine.response("location", "normal", "234000050"));
        var other = vehicles(engine.response("location", "normal", "204000057"));
        var sparse = vehicles(engine.response("location", "sparse", "234000050"));
        assertTrue(first.stream().noneMatch(before -> sparse.stream()
            .anyMatch(after -> before.get("vehId").equals(after.get("vehId")))));
        assertEquals(other, vehicles(engine.response("location", "normal", "204000057")));
        assertEquals(sparse, vehicles(engine.response("location", "sparse", "234000050")));
    }

    @Test
    void eachDirectionLeavesItsTerminalAndRestartsAsANewRun() throws Exception {
        var data = ReplayEngine.readRoutes(Path.of(System.getProperty("routes.file")));
        for (var route : data.routes()) {
            for (String direction : List.of("U", "D")) {
                var time = new AtomicLong();
                var engine = engine(data, time);
                String plate = "LOCAL-" + route.displayName() + "-0-" + direction;
                final int first = direction.equals("U") ? 1 : route.turnSequence() + 1;
                final int count = direction.equals("U") ? route.turnSequence() : route.stops().size() - route.turnSequence();
                var initial = vehicles(engine.response("location", "normal", route.routeId())).stream()
                    .filter(bus -> plate.equals(bus.get("plateNo"))).findFirst().orElseThrow();
                time.set((count - 1L) * 30_000_000_000L);
                var terminal = vehicles(engine.response("location", "normal", route.routeId())).stream()
                    .filter(bus -> initial.get("vehId").equals(bus.get("vehId"))).findFirst().orElseThrow();
                assertEquals(first + count - 1, terminal.get("stationSeq"));
                time.set(count * 30_000_000_000L);
                assertTrue(vehicles(engine.response("location", "normal", route.routeId())).stream()
                    .noneMatch(bus -> initial.get("vehId").equals(bus.get("vehId"))));
                time.set((count * 30L + 60) * 1_000_000_000L);
                var restarted = vehicles(engine.response("location", "normal", route.routeId())).stream()
                    .filter(bus -> plate.equals(bus.get("plateNo"))).findFirst().orElseThrow();
                assertEquals(first, restarted.get("stationSeq"));
                assertNotEquals(initial.get("vehId"), restarted.get("vehId"));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> vehicles(ReplayEngine.Reply reply) {
        return (List<Map<String, Object>>) ((Map<?, ?>) ((Map<?, ?>) reply.body().get("response"))
            .get("msgBody")).get("busLocationList");
    }
}
