package com.gustler.localgbis;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.common.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ReplayArrivalsTest {
    private final Path routes = Path.of(System.getProperty("routes.file"));

    @Test
    void predictionsUseTheSameVehiclesDirectionSeatsAndClockAsLocations() throws Exception {
        var dataset = ReplayEngine.readRoutes(routes);
        var time = new AtomicLong();
        var engine = engine(dataset, time);
        for (var route : dataset.routes()) {
            var vehicles = rows(engine.response("location", "normal", route.routeId()), "busLocationList");
            var target = route.stops().get(4);
            var row = arrival(engine, target.stationId(), route.routeId(), target.sequence(), "normal");
            assertEquals(vehicles.get(0).get("vehId"), row.get("vehId1"));
            assertEquals(vehicles.get(0).get("remainSeatCnt"), row.get("remainSeatCnt1"));
            assertEquals(120L, row.get("predictTimeSec1"));
            assertEquals(4, row.get("locationNo1"));
            assertFalse(row.containsKey("vehId2"), "반대 방향 차량을 두 번째 ETA로 사용하지 않는다");
        }
        var route = dataset.routes().get(0);
        var target = route.stops().get(4);
        time.set(35_000_000_000L);
        var later = arrival(engine, target.stationId(), route.routeId(), target.sequence(), "normal");
        assertEquals(85L, later.get("predictTimeSec1"));
        assertEquals(3, later.get("locationNo1"));
        time.set(150_000_000_000L);
        assertFalse(arrival(engine, target.stationId(), route.routeId(), target.sequence(), "normal").containsKey("vehId1"));
    }

    @Test
    void absentUnknownAndCatalogVehiclesDoNotBecomeInventedPredictions() throws Exception {
        var dataset = ReplayEngine.readAllRoutes(routes);
        var engine = engine(dataset, new AtomicLong());
        var route = dataset.routes().get(0);
        var first = route.stops().get(0);
        assertFalse(arrival(engine, first.stationId(), route.routeId(), first.sequence(), "normal").containsKey("predictTimeSec1"));
        for (String mode : List.of("empty", "unreadable", "down-only")) {
            var target = route.stops().get(4);
            assertFalse(arrival(engine, target.stationId(), route.routeId(), target.sequence(), mode).containsKey("predictTimeSec1"));
        }
        var catalog = dataset.routes().get(8);
        var target = catalog.stops().get(4);
        assertFalse(arrival(engine, target.stationId(), catalog.routeId(), target.sequence(), "normal").containsKey("predictTimeSec1"));
        assertTrue(rows(engine.response("arrival", "normal", "999999999"), "busArrivalList").isEmpty());
        assertEquals(503, engine.response("arrival", "upstream-error", first.stationId()).status());
        assertFalse(((Map<?, ?>) engine.response("arrival", "invalid-response", first.stationId()).body().get("response")).containsKey("msgBody"));
    }

    @Test
    void multipleVehiclesAreOrderedAndMissingIdentityAndSeatsStayMissing() throws Exception {
        var dataset = ReplayEngine.readRoutes(routes);
        var route = dataset.routes().get(0);
        var engine = new ReplayEngine(dataset, new ReplayEngine.Configuration("local-replay-v1", 30, 60, 3),
            Clock.systemUTC(), () -> 0, "eta-test");
        var target = route.stops().get(15);
        var row = arrival(engine, target.stationId(), route.routeId(), target.sequence(), "normal");
        assertTrue((long) row.get("predictTimeSec1") <= (long) row.get("predictTimeSec2"));
        assertNotEquals(row.get("vehId1"), row.get("vehId2"));
        assertFalse(row.containsKey("vehId3"));
        var anonymous = arrival(engine, target.stationId(), route.routeId(), target.sequence(), "anonymous");
        assertFalse(anonymous.containsKey("vehId1"));
        assertTrue((long) anonymous.get("predictTimeSec1") > 0);
        assertFalse(arrival(engine, target.stationId(), route.routeId(), target.sequence(), "missing-seat").containsKey("remainSeatCnt1"));
        assertEquals(-1, arrival(engine, target.stationId(), route.routeId(), target.sequence(), "unknown-seat").get("remainSeatCnt1"));
    }

    @Test
    void realWireMockEndpointAcceptsBothDevelopmentKeysAndStationParameter() throws Exception {
        var engine = engine(ReplayEngine.readAllRoutes(routes), new AtomicLong());
        var server = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort()
            .usingFilesUnderDirectory(routes.getParent().getParent().resolve("wiremock").toString())
            .extensions(new GbisReplayTransformer(engine)));
        server.start();
        try {
            var route = ReplayEngine.readRoutes(routes).routes().get(0);
            String station = route.stops().get(4).stationId();
            var client = HttpClient.newHttpClient();
            for (String key : List.of("local-only-placeholder", "shared-dev-placeholder")) {
                var response = client.send(HttpRequest.newBuilder(URI.create(server.baseUrl()
                    + "/busarrivalservice/v2/getBusArrivalListv2?format=json&stationId=" + station
                    + "&serviceKey=" + key)).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                var body = Json.node(response.body()).path("response");
                assertEquals(0, body.path("msgHeader").path("resultCode").asInt());
                assertTrue(body.path("msgBody").path("busArrivalList").isArray());
                assertTrue(body.path("msgBody").path("busArrivalList").toString().contains("predictTimeSec1"));
            }
            var rejected = client.send(HttpRequest.newBuilder(URI.create(server.baseUrl()
                + "/busarrivalservice/v2/getBusArrivalListv2?format=json&stationId=" + station
                + "&serviceKey=unsupported")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(404, rejected.statusCode());
        } finally {
            server.stop();
        }
    }

    private ReplayEngine engine(ReplayEngine.Dataset dataset, AtomicLong time) {
        return new ReplayEngine(dataset, new ReplayEngine.Configuration("local-replay-v1", 30, 60),
            Clock.fixed(Instant.parse("2026-10-16T00:00:00Z"), ZoneOffset.UTC), time::get, "eta-test");
    }

    private static Map<String, Object> arrival(ReplayEngine engine, String station, String route,
                                              final int sequence, String mode) {
        return rows(engine.response("arrival", mode, station), "busArrivalList").stream()
            .filter(row -> row.get("routeId").equals(route) && row.get("staOrder").equals(sequence))
            .findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(ReplayEngine.Reply reply, String field) {
        return (List<Map<String, Object>>) ((Map<?, ?>) ((Map<?, ?>) reply.body().get("response")).get("msgBody")).get(field);
    }
}
