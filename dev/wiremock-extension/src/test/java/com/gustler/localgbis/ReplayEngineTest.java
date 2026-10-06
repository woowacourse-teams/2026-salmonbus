package com.gustler.localgbis;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReplayEngineTest {
    private ReplayEngine.Dataset dataset;
    private ReplayEngine engine;
    private AtomicLong time;

    @BeforeEach
    void prepare() throws Exception {
        dataset = ReplayEngine.readRoutes(Path.of(System.getProperty("routes.file")));
        time = new AtomicLong();
        engine = new ReplayEngine(dataset, new ReplayEngine.Configuration("local-replay-v1", 30, 60),
            Clock.fixed(Instant.parse("2026-10-06T16:00:00Z"), ZoneOffset.UTC), time::get, "testrun1");
    }

    @Test
    void metadataMatchesEveryRouteAndDoesNotStartPlayback() {
        final int expectedStops = 643;
        int stops = 0;
        for (var route : dataset.routes()) {
            var info = engine.response("route-info", "normal", route.routeId());
            assertEquals(200, info.status());
            Map<?, ?> item = (Map<?, ?>) body(info).get("busRouteInfoItem");
            assertEquals(route.displayName(), item.get("routeName"));
            var response = engine.response("route-stations", "normal", route.routeId());
            List<?> list = (List<?>) body(response).get("busRouteStationList");
            assertEquals(route.stops().size(), list.size());
            for (int index = 0; index < list.size(); index++) {
                Map<?, ?> station = (Map<?, ?>) list.get(index);
                assertEquals(index + 1, station.get("stationSeq"));
                assertEquals(route.stops().get(index).stationId(), station.get("stationId"));
                assertEquals(route.stops().get(index).x(), station.get("x"));
                assertEquals(route.turnSequence(), station.get("turnSeq"));
            }
            stops += list.size();
        }
        assertEquals(expectedStops, stops);
        time.set(90_000_000_000L);
        assertEquals(1, vehicles(engine.response("location", "normal", "234000050")).get(0).get("stationSeq"));
    }

    @Test
    void retriesAndConcurrentRequestsDoNotMoveVehicles() {
        var first = engine.response("location", "normal", "234000050");
        IntStream.range(0, 64).parallel().forEach(index ->
            assertEquals(first, engine.response("location", "normal", "234000050")));
        time.set(20_000_000_000L);
        assertEquals(first, engine.response("location", "normal", "234000050"));
        time.set(31_000_000_000L);
        var second = vehicles(engine.response("location", "normal", "234000050"));
        assertEquals(2, second.get(0).get("stationSeq"));
        assertEquals(33, second.get(0).get("remainSeatCnt"));
        assertEquals(vehicles(first).get(0).get("vehId"), second.get(0).get("vehId"));
    }

    @Test
    void routeFaultsLeaveOtherRoutesWorkingAndDoNotStartPlayback() {
        var error = engine.response("location", "upstream-error", "234000050");
        assertEquals(503, error.status());
        assertEquals(1, header(error).get("resultCode"));
        var empty = engine.response("location", "empty", "234000050");
        assertEquals(4, header(empty).get("resultCode"));
        assertFalse(((Map<?, ?>) empty.body().get("response")).containsKey("msgBody"));
        time.set(90_000_000_000L);
        var missingSeats = engine.response("location", "unknown-seat", "234000050");
        assertTrue(vehicles(missingSeats).stream().allMatch(value -> value.get("remainSeatCnt").equals(-1)));
        var other = vehicles(engine.response("location", "normal", "204000057"));
        assertEquals(2, other.size());
        assertEquals(1, other.get(0).get("stationSeq"));
    }

    @Test
    void reachesBothEndsPausesAndRestartsWithNewVehicleIds() {
        for (var route : dataset.routes()) {
            time.set(0);
            var fresh = new ReplayEngine(dataset, new ReplayEngine.Configuration("local-replay-v1", 30, 60),
                Clock.systemUTC(), time::get, "testrun1");
            var initial = vehicles(fresh.response("location", "normal", route.routeId()));
            assertEquals(1, initial.get(0).get("stationSeq"));
            assertEquals(route.turnSequence(), initial.get(1).get("stationSeq"));
            final int downCount = route.stops().size() - route.turnSequence() + 1;
            time.set((route.turnSequence() - 1L) * 30_000_000_000L);
            var atTurn = vehicles(fresh.response("location", "normal", route.routeId()));
            assertEquals(route.turnSequence(), atTurn.get(0).get("stationSeq"));
            assertEquals(0, atTurn.get(0).get("stateCd"));
            time.set((downCount - 1L) * 30_000_000_000L);
            var atEnd = vehicles(fresh.response("location", "normal", route.routeId()));
            var down = atEnd.stream().filter(value -> value.get("plateNo").toString().endsWith("-D")).findFirst().orElseThrow();
            assertEquals(route.stops().size(), down.get("stationSeq"));
            time.set(Math.max(route.turnSequence(), downCount) * 30_000_000_000L);
            assertEquals(4, header(fresh.response("location", "normal", route.routeId())).get("resultCode"));
            time.addAndGet(60_000_000_000L);
            var restarted = vehicles(fresh.response("location", "normal", route.routeId()));
            assertEquals(1, restarted.get(0).get("stationSeq"));
            assertNotEquals(initial.get(0).get("vehId"), restarted.get(0).get("vehId"));
        }
    }

    @Test
    void rejectsUnknownRoutesOperationsAndScenarios() {
        assertEquals(404, engine.response("location", "normal", "999999999").status());
        assertEquals(400, engine.response("wrong", "normal", "234000050").status());
        assertEquals(400, engine.response("location", "wrong", "234000050").status());
        assertThrows(IllegalArgumentException.class, () -> new ReplayEngine(dataset,
            new ReplayEngine.Configuration("local-replay-v1", 0, 60), Clock.systemUTC(), time::get, "testrun1"));
    }

    private static Map<?, ?> body(ReplayEngine.Reply reply) {
        return (Map<?, ?>) ((Map<?, ?>) reply.body().get("response")).get("msgBody");
    }

    private static Map<?, ?> header(ReplayEngine.Reply reply) {
        return (Map<?, ?>) ((Map<?, ?>) reply.body().get("response")).get("msgHeader");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> vehicles(ReplayEngine.Reply reply) {
        return (List<Map<String, Object>>) body(reply).get("busLocationList");
    }
}
