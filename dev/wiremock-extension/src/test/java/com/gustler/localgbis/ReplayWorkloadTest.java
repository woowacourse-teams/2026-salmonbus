package com.gustler.localgbis;

import com.github.tomakehurst.wiremock.common.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReplayWorkloadTest {
    private final Path routes = Path.of(System.getProperty("routes.file"));

    @Test
    void largerDirectionalFleetsKeepDistinctIdentitiesAndValidPositionsAcrossTrips() throws Exception {
        var dataset = ReplayEngine.readRoutes(routes);
        var clock = new AtomicLong();
        var fleet = new ReplayEngine.Fleet(14, 9);
        var configuration = new ReplayEngine.Configuration("local-replay-v1", 30, 1, 6,
            Map.of("1650", fleet));
        var engine = new ReplayEngine(dataset, configuration, Clock.systemUTC(), clock::get, "workload");
        var route = dataset.routes().stream().filter(r -> r.displayName().equals("1650")).findFirst().orElseThrow();
        double total = 0;
        for (int seconds = 0; seconds < 7_200; seconds += 15) {
            clock.set(seconds * 1_000_000_000L);
            var buses = vehicles(engine.response("location", "normal", route.routeId()));
            assertEquals(buses.size(), buses.stream().map(v -> v.get("vehId")).distinct().count());
            assertEquals(buses.size(), buses.stream().map(v -> v.get("plateNo")).distinct().count());
            assertTrue(buses.stream().filter(v -> v.get("vehId").toString().endsWith("U")).count() <= fleet.up());
            assertTrue(buses.stream().filter(v -> v.get("vehId").toString().endsWith("D")).count() <= fleet.down());
            for (var bus : buses) {
                final int sequence = (Integer) bus.get("stationSeq");
                assertTrue(sequence >= 1 && sequence <= route.stops().size());
                assertEquals(route.stops().get(sequence - 1).stationId(), bus.get("stationId"));
                assertTrue(bus.get("vehId").toString().endsWith("U")
                    ? sequence <= route.turnSequence() : sequence > route.turnSequence());
            }
            total += buses.size();
        }
        assertTrue(total / 480 >= (fleet.up() + fleet.down()) * 0.98,
            "The configured active fleet must be maintained across replay cycles");
    }

    @Test
    void routeOverrideDoesNotChangeOtherRoutesOrRestoreAnEmptyDirection() throws Exception {
        var configuration = new ReplayEngine.Configuration("local-replay-v1", 30, 1, 6,
            Map.of("1650", new ReplayEngine.Fleet(12, 0)));
        var engine = new ReplayEngine(ReplayEngine.readRoutes(routes), configuration,
            Clock.systemUTC(), () -> 0, "workload");
        var buses = vehicles(engine.response("location", "normal", "234000050"));
        assertEquals(12, buses.size());
        assertTrue(buses.stream().allMatch(v -> v.get("vehId").toString().endsWith("U")));
        assertEquals(12, vehicles(engine.response("location", "normal", "204000057")).size());
        assertEquals(1, vehicles(engine.response("location", "single", "234000050")).size());
    }

    @Test
    void fleetLimitsAndUnknownRoutesAreRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new ReplayEngine.Fleet(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ReplayEngine.Fleet(1, 65));
        var data = ReplayEngine.readRoutes(routes);
        var configuration = new ReplayEngine.Configuration("local-replay-v1", 30, 1, 6,
            Map.of("unknown-route", new ReplayEngine.Fleet(1, 1)));
        assertThrows(IllegalArgumentException.class, () -> new ReplayEngine(data, configuration,
            Clock.systemUTC(), () -> 0, "workload"));
    }

    @Test
    void sharedProfileLoadsWithoutChangingThePersonalLocalProfile() throws Exception {
        var dev = routes.getParent().getParent();
        var shared = Json.read(Files.readString(dev.resolve("server/replay.json")), ReplayEngine.Configuration.class);
        var local = Json.read(Files.readString(dev.resolve("scenarios/replay.json")), ReplayEngine.Configuration.class);
        var data = ReplayEngine.readRoutes(routes);
        assertTrue(local.routeVehicles().isEmpty());
        assertEquals(6, local.vehiclesPerDirection());
        assertEquals(data.routes().stream().map(ReplayEngine.Route::displayName).collect(java.util.stream.Collectors.toSet()),
            shared.routeVehicles().keySet());
        assertTrue(shared.routeVehicles().values().stream().anyMatch(f -> f.up() > 6 || f.down() > 6));
        var engine = new ReplayEngine(data, shared, Clock.systemUTC(), () -> 0, "workload");
        for (var route : data.routes()) {
            var fleet = shared.routeVehicles().get(route.displayName());
            assertEquals(fleet.up() + fleet.down(), vehicles(engine.response("location", "normal", route.routeId())).size());
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> vehicles(ReplayEngine.Reply reply) {
        return (List<Map<String, Object>>) ((Map<?, ?>) ((Map<?, ?>) reply.body().get("response"))
            .get("msgBody")).get("busLocationList");
    }
}
