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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class AdditionalRoutesReplayTest {
    private static final List<String> ADDED = List.of("204000081", "234000002", "234000313", "234000878",
        "234000882", "204000082", "234001739", "233000266", "204000170", "234000879", "228000393", "200000314");

    @Test
    void 추가한_12개_노선은_같은_차량이_이동하며_좌석과_관측시각을_갱신한다() throws Exception {
        var dataset = ReplayEngine.readAllRoutes(Path.of(System.getProperty("routes.file")));
        var seconds = new AtomicLong();
        var clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return Instant.parse("2026-10-16T00:00:00Z").plusSeconds(seconds.get()); }
        };
        for (String id : ADDED) {
            seconds.set(0);
            var engine = new ReplayEngine(dataset, new ReplayEngine.Configuration("local-replay-v1", 30, 60, 2),
                clock, () -> seconds.get() * 1_000_000_000L, "newroutes");
            var route = dataset.routes().stream().filter(r -> r.routeId().equals(id)).findFirst().orElseThrow();
            var before = engine.response("location", "normal", id);
            var first = vehicles(before).get(0);

            seconds.set(30);
            var after = engine.response("location", "normal", id);
            var next = vehicles(after).get(0);

            assertEquals(first.get("vehId"), next.get("vehId"));
            assertEquals(2, next.get("stationSeq"));
            assertEquals(route.stops().get(1).stationId(), next.get("stationId"));
            assertNotEquals(first.get("remainSeatCnt"), next.get("remainSeatCnt"));
            assertNotEquals(header(before).get("queryTime"), header(after).get("queryTime"));
            assertEquals(1, next.get("stateCd"));
            seconds.set((route.turnSequence() - 1L) * 30);
            assertEquals(route.turnSequence(), vehicles(engine.response("location", "normal", id)).get(0).get("stationSeq"));
            seconds.set(route.turnSequence() * 30L + 60);
            var restarted = vehicles(engine.response("location", "normal", id)).get(0);
            assertEquals(1, restarted.get("stationSeq"));
            assertEquals(first.get("vehId"), restarted.get("vehId"));
        }
    }

    @Test
    void 실제_WireMock_매핑이_20개_노선을_응답하고_한_노선_실패는_다른_노선을_막지_않는다() throws Exception {
        var file = Path.of(System.getProperty("routes.file"));
        var dataset = ReplayEngine.readAllRoutes(file);
        var engine = new ReplayEngine(dataset, new ReplayEngine.Configuration("local-replay-v1", 30, 60),
            Clock.systemUTC(), () -> 0, "newroutes");
        var server = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort()
            .usingFilesUnderDirectory(file.getParent().getParent().resolve("wiremock").toString())
            .extensions(new GbisReplayTransformer(engine)));
        server.start();
        try {
            var client = HttpClient.newHttpClient();
            var selected = dataset.routes().stream().filter(r -> ReplayEngine.REPLAY_ROUTES.contains(r.displayName())).toList();

            for (var route : selected) {
                var response = get(client, server.baseUrl(), route.routeId());
                assertEquals(200, response.statusCode(), route.displayName());
                assertFalse(Json.node(response.body()).at("/response/msgBody/busLocationList").isEmpty());
            }
            ScenarioControl.configure(client, server.baseUrl(), Map.of(ADDED.get(0), "upstream-error"));

            assertEquals(20, selected.size());
            assertEquals(41, dataset.routes().stream().map(ReplayEngine.Route::routeId).distinct().count());
            assertEquals(503, get(client, server.baseUrl(), ADDED.get(0)).statusCode());
            assertEquals(200, get(client, server.baseUrl(), ADDED.get(1)).statusCode());
            assertEquals(200, get(client, server.baseUrl(), "204000057").statusCode());
        } finally {
            server.stop();
        }
    }

    private static HttpResponse<String> get(HttpClient client, String base, String id) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base
            + "/buslocationservice/v2/getBusLocationListv2?format=json&routeId=" + id
            + "&serviceKey=shared-dev-placeholder")).build(), HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> vehicles(ReplayEngine.Reply reply) {
        return (List<Map<String, Object>>) ((Map<?, ?>) ((Map<?, ?>) reply.body().get("response"))
            .get("msgBody")).get("busLocationList");
    }

    private static Map<?, ?> header(ReplayEngine.Reply reply) {
        return (Map<?, ?>) ((Map<?, ?>) reply.body().get("response")).get("msgHeader");
    }
}
