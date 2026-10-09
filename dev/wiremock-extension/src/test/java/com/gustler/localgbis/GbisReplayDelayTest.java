package com.gustler.localgbis;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.*;

class GbisReplayDelayTest {
    @Test
    void responseTransformationPreservesConfiguredDelay() throws Exception {
        var data = ReplayEngine.readRoutes(Path.of(System.getProperty("routes.file")));
        var engine = new ReplayEngine(data, new ReplayEngine.Configuration("local-replay-v1", 30, 60, 6),
            Clock.systemUTC(), () -> 0, "testrun1");
        var server = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort()
            .extensions(new GbisReplayTransformer(engine)));
        server.start();
        try {
            server.stubFor(get(urlPathEqualTo("/location")).willReturn(aResponse().withFixedDelay(250)
                .withTransformers("gbis-replay").withTransformerParameter("operation", "location")));
            var client = HttpClient.newHttpClient();
            client.send(HttpRequest.newBuilder(URI.create(server.baseUrl() + "/__admin/mappings")).build(),
                HttpResponse.BodyHandlers.discarding());
            final long started = System.nanoTime();
            var response = client.send(HttpRequest.newBuilder(URI.create(
                server.baseUrl() + "/location?routeId=204000070")).timeout(Duration.ofSeconds(3)).build(),
                HttpResponse.BodyHandlers.ofString());
            final long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("busLocationList"));
            assertTrue(elapsedMs >= 200, "Configured 250ms delay was lost: " + elapsedMs);
        } finally {
            server.stop();
        }
    }
}
