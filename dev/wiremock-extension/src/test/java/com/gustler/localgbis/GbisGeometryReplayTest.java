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
import java.util.Map;
import org.junit.jupiter.api.Test;

class GbisGeometryReplayTest {
    @Test
    void defaultMappingsReplayExactRoadPointsForEveryRouteWithBothDevelopmentKeys() throws Exception {
        Path routeFile = Path.of(System.getProperty("routes.file"));
        var data = ReplayEngine.readAllRoutes(routeFile);
        var shapes = RouteShapes.read(routeFile.resolveSibling("route-shapes.json"), data);
        var engine = new ReplayEngine(routeFile, routeFile.getParent().getParent().resolve("scenarios/replay.json"));
        var server = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort()
            .usingFilesUnderDirectory(routeFile.getParent().getParent().resolve("wiremock").toString())
            .extensions(new GbisReplayTransformer(engine)));
        server.start();
        try {
            var client = HttpClient.newHttpClient();
            for (var route : data.routes()) {
                for (String key : new String[]{"local-only-placeholder", "shared-dev-placeholder"}) {
                    var response = get(client, server.baseUrl(), route.routeId(), key);
                    assertEquals(200, response.statusCode(), route.displayName());
                    var body = Json.node(response.body()).path("response");
                    assertEquals(0, body.path("msgHeader").path("resultCode").asInt());
                    var points = body.path("msgBody").path("busRouteLineList");
                    var expected = shapes.get(route.routeId());
                    assertEquals(expected.size(), points.size(), route.displayName());
                    for (int index = 0; index < points.size(); index++) {
                        var point = points.get(index);
                        assertEquals(route.routeId(), point.path("routeId").asText());
                        assertEquals(index + 1, point.path("lineSeq").asInt());
                        assertEquals(expected.get(index).x(), point.path("x").asDouble());
                        assertEquals(expected.get(index).y(), point.path("y").asDouble());
                    }
                }
            }
            assertEquals(404, get(client, server.baseUrl(), data.routes().get(0).routeId(), "unsupported").statusCode());
            assertEquals(404, get(client, server.baseUrl(), "999999999", "local-only-placeholder").statusCode());
            for (var route : data.routes().subList(8, data.routes().size())) {
                assertEquals(404, engine.response("location", "normal", route.routeId()).status());
            }
        } finally {
            server.stop();
        }
    }

    @Test
    void metadataOnlyConstructorReportsMissingGeometryWithoutSynthesizingStops() throws Exception {
        var data = ReplayEngine.readRoutes(Path.of(System.getProperty("routes.file")));
        var engine = new ReplayEngine(data, new ReplayEngine.Configuration("local-replay-v1", 30, 60),
            Clock.systemUTC(), () -> 0, "test");
        var reply = engine.response("route-line", "normal", data.routes().get(0).routeId());
        assertEquals(200, reply.status());
        var response = (Map<?, ?>) reply.body().get("response");
        assertEquals(4, ((Map<?, ?>) response.get("msgHeader")).get("resultCode"));
        assertFalse(response.containsKey("msgBody"));
    }

    private static HttpResponse<String> get(HttpClient client, String base, String routeId, String key) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base
            + "/busrouteservice/v2/getBusRouteLineListv2?format=json&routeId=" + routeId + "&serviceKey=" + key)).build(),
            HttpResponse.BodyHandlers.ofString());
    }
}
