package com.gustler.localgbis;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReplayFleetTest {
    @Test
    void distributesDistinctVehiclesAndAdvancesEachByElapsedTime() throws Exception {
        var data = ReplayEngine.readRoutes(Path.of(System.getProperty("routes.file")));
        var time = new AtomicLong();
        var engine = new ReplayEngine(data, new ReplayEngine.Configuration("local-replay-v1", 30, 60, 3),
            Clock.systemUTC(), time::get, "testrun1");
        for (var route : data.routes()) {
            var first = vehicles(engine.response("location", "normal", route.routeId()));
            assertTrue(first.size() >= 4 && first.size() <= 6);
            assertEquals(first.size(), first.stream().map(v -> v.get("vehId")).distinct().count());
            assertEquals(first.size(), first.stream().map(v -> v.get("plateNo")).distinct().count());
            for (var bus : first) {
                final int sequence = (Integer) bus.get("stationSeq");
                assertEquals(route.stops().get(sequence - 1).stationId(), bus.get("stationId"));
                assertTrue((Integer) bus.get("remainSeatCnt") >= 0 && (Integer) bus.get("remainSeatCnt") <= 44);
            }
        }
        var first = vehicles(engine.response("location", "normal", "204000070"));
        time.set(30_000_000_000L);
        var second = vehicles(engine.response("location", "normal", "204000070"));
        for (var bus : first) {
            var next = second.stream().filter(v -> v.get("vehId").equals(bus.get("vehId"))).findFirst();
            next.ifPresent(v -> assertEquals((Integer) bus.get("stationSeq") + 1, v.get("stationSeq")));
        }
    }

    @Test
    void changesSeatsWithoutChangingVehicleIdentityOrPosition() throws Exception {
        var data = ReplayEngine.readRoutes(Path.of(System.getProperty("routes.file")));
        var engine = new ReplayEngine(data, new ReplayEngine.Configuration("local-replay-v1", 30, 60, 3),
            Clock.systemUTC(), () -> 0, "testrun1");
        var baseline = vehicles(engine.response("location", "normal", "204000070"));
        for (var entry : Map.of("full", 0, "few-seats", 3, "unknown-seat", -1).entrySet()) {
            var buses = vehicles(engine.response("location", entry.getKey(), "204000070"));
            assertEquals(baseline.size(), buses.size());
            for (int index = 0; index < buses.size(); index++) {
                assertEquals(baseline.get(index).get("vehId"), buses.get(index).get("vehId"));
                assertEquals(baseline.get(index).get("stationSeq"), buses.get(index).get("stationSeq"));
                assertEquals(entry.getValue(), buses.get(index).get("remainSeatCnt"));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> vehicles(ReplayEngine.Reply reply) {
        return (List<Map<String, Object>>) ((Map<?, ?>) ((Map<?, ?>) reply.body().get("response"))
            .get("msgBody")).get("busLocationList");
    }
}
