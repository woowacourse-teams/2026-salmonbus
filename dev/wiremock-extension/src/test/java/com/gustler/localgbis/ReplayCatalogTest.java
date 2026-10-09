package com.gustler.localgbis;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReplayCatalogTest {
    @Test
    void catalogMetadataDoesNotEnableVehiclePlayback() throws Exception {
        var data = ReplayEngine.readAllRoutes(Path.of(System.getProperty("routes.file")));
        var time = new AtomicLong();
        var engine = new ReplayEngine(data, new ReplayEngine.Configuration("local-replay-v1", 30, 60),
            Clock.systemUTC(), time::get, "testrun1");

        assertEquals(41, data.routes().size());
        for (var route : data.routes().subList(8, data.routes().size())) {
            var info = engine.response("route-info", "normal", route.routeId());
            assertEquals(200, info.status());
            var item = (Map<?, ?>) body(info).get("busRouteInfoItem");
            assertEquals(route.displayName(), item.get("routeName"));
            var stations = (List<?>) body(engine.response("route-stations", "normal", route.routeId()))
                .get("busRouteStationList");
            assertEquals(route.stops().size(), stations.size());
            for (String mode : List.of("normal", "empty", "unknown-seat", "upstream-error")) {
                var response = engine.response("location", mode, route.routeId());
                assertEquals(404, response.status());
                assertEquals("local-route-observation-disabled", response.body().get("error"));
            }
        }

        time.set(90_000_000_000L);
        var replay = (List<?>) body(engine.response("location", "normal", "234000050"))
            .get("busLocationList");
        assertEquals(1, ((Map<?, ?>) replay.get(0)).get("stationSeq"));
    }

    @Test
    void excludesUnverifiedSeatsEmptyAndSpecialServiceCandidates() throws Exception {
        var data = ReplayEngine.readAllRoutes(Path.of(System.getProperty("routes.file")));
        var names = data.routes().stream().map(ReplayEngine.Route::displayName).toList();

        assertTrue(names.containsAll(List.of("1007", "1500-2", "6003", "G8110")));
        for (String excluded : List.of("8106", "8302", "9000-1", "9401", "9401-1", "9409", "5700A")) {
            assertFalse(names.contains(excluded));
        }
        assertTrue(names.stream().noneMatch(name -> name.startsWith("P") || name.contains("예약") || name.contains("급행")));
    }

    private static Map<?, ?> body(ReplayEngine.Reply reply) {
        return (Map<?, ?>) ((Map<?, ?>) reply.body().get("response")).get("msgBody");
    }
}
