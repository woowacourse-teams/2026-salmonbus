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
import java.util.HashSet;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReplayScenarioArrivalsTest {
    private final Path routes = Path.of(System.getProperty("routes.file"));

    @Test
    void scenarioControlKeepsArrivalVehiclesAlignedAndRetainsOtherRouteSelections() throws Exception {
        var dataset = ReplayEngine.readAllRoutes(routes);
        var engine = new ReplayEngine(dataset, new ReplayEngine.Configuration("local-replay-v1", 30, 60, 3),
            Clock.systemUTC(), () -> 0, "scenario-test");
        var server = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort()
            .usingFilesUnderDirectory(routes.getParent().getParent().resolve("wiremock").toString())
            .extensions(new GbisReplayTransformer(engine)));
        server.start();
        try {
            var client = HttpClient.newHttpClient();
            var route = ReplayEngine.readRoutes(routes).routes().get(0);
            var other = ReplayEngine.readRoutes(routes).routes().get(1);
            var target = route.stops().get(15);
            ScenarioControl.configure(client, server.baseUrl(), Map.of(other.routeId(), "empty"));
            for (String mode : java.util.List.of("sparse", "bunched", "normal")) {
                ScenarioControl.configure(client, server.baseUrl(), Map.of(route.routeId(), mode));
                var locations = Json.node(get(client, server, "/buslocationservice/v2/getBusLocationListv2?routeId="
                    + route.routeId()).body()).path("response").path("msgBody").path("busLocationList");
                var identities = new HashSet<String>();
                locations.forEach(vehicle -> identities.add(vehicle.path("vehId").asText()));
                String predicted = "";
                for (int attempt = 0; attempt < 2; attempt++) {
                    var arrivals = Json.node(get(client, server, "/busarrivalservice/v2/getBusArrivalListv2?stationId="
                        + target.stationId()).body()).path("response").path("msgBody").path("busArrivalList");
                    for (var row : arrivals) {
                        if (row.path("routeId").asText().equals(route.routeId())
                            && row.path("staOrder").asInt() == target.sequence()) {
                            assertTrue(row.has("predictTimeSec1"));
                            assertTrue(identities.contains(row.path("vehId1").asText()));
                            if (!predicted.isEmpty()) assertEquals(predicted, row.path("vehId1").asText());
                            predicted = row.path("vehId1").asText();
                        }
                    }
                }
                assertFalse(predicted.isEmpty());
                assertEquals(4, Json.node(get(client, server, "/buslocationservice/v2/getBusLocationListv2?routeId="
                    + other.routeId()).body()).path("response").path("msgHeader").path("resultCode").asInt());
            }
            ScenarioControl.configure(client, server.baseUrl(), Map.of(route.routeId(), "empty"));
            var empty = get(client, server, "/busarrivalservice/v2/getBusArrivalListv2?stationId=" + target.stationId());
            for (var row : Json.node(empty.body()).path("response").path("msgBody").path("busArrivalList")) {
                if (row.path("routeId").asText().equals(route.routeId())) assertFalse(row.has("predictTimeSec1"));
            }
            ScenarioControl.configure(client, server.baseUrl(), Map.of(route.routeId(), "upstream-error"));
            assertEquals(503, get(client, server, "/busarrivalservice/v2/getBusArrivalListv2?stationId="
                + target.stationId()).statusCode());
            ScenarioControl.configure(client, server.baseUrl(), Map.of(route.routeId(), "invalid-response"));
            assertFalse(Json.node(get(client, server, "/busarrivalservice/v2/getBusArrivalListv2?stationId="
                + target.stationId()).body()).path("response").has("msgBody"));
            assertEquals(7_000, engine.response("arrival", "normal", target.stationId(),
                Map.of(route.routeId(), "timeout")).delayMillis());
        } finally {
            server.stop();
        }
    }

    private HttpResponse<String> get(HttpClient client, WireMockServer server, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(server.baseUrl() + path
            + "&serviceKey=shared-dev-placeholder&format=json")).build(), HttpResponse.BodyHandlers.ofString());
    }
}
