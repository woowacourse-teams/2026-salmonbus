package com.gustler.localgbis;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class GbisReplayKeyTest {
    @Test
    void defaultMappingsAcceptOnlyTheTwoDevelopmentKeys() throws Exception {
        Path routeFile = Path.of(System.getProperty("routes.file"));
        var engine = new ReplayEngine(ReplayEngine.readRoutes(routeFile),
            new ReplayEngine.Configuration("local-replay-v1", 30, 60, 6), Clock.systemUTC(), () -> 0, "testrun1");
        var server = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort()
            .usingFilesUnderDirectory(routeFile.getParent().getParent().resolve("wiremock").toString())
            .extensions(new GbisReplayTransformer(engine)));
        server.start();
        try {
            var client = HttpClient.newHttpClient();
            for (String key : new String[]{"local-only-placeholder", "shared-dev-placeholder", "another-test-key"}) {
                var response = client.send(HttpRequest.newBuilder(URI.create(server.baseUrl()
                    + "/buslocationservice/v2/getBusLocationListv2?format=json&routeId=204000070&serviceKey=" + key)).build(),
                    HttpResponse.BodyHandlers.ofString());
                assertEquals(key.equals("another-test-key") ? 404 : 200, response.statusCode());
                if (!key.equals("another-test-key")) {
                    assertTrue(response.body().contains("busLocationList"));
                }
            }
        } finally {
            server.stop();
        }
    }
}
