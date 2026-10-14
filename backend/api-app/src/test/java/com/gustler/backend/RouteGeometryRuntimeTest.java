package com.gustler.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.arrival.support.ArrivalRuntime;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class RouteGeometryRuntimeTest {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @Timeout(90)
    void 별도_Worker가_형상을_수집하고_API가_836점과_정류장_캐시와_압축을_제공한다() throws Exception {
        try (var runtime = new ArrivalRuntime()) {
            URI uri = runtime.arrivalsUri().resolve("/api/v2/routes/204000057/geometry");
            assertThat(JSON.readTree(get(uri).body()).path("status").asString()).isEqualTo("UNAVAILABLE");
            runtime.source.enableGeometry();
            runtime.startWorker(false, Map.of("SPRING_APPLICATION_JSON", """
                {"collection":{"enabled":false},
                 "route-geometry":{"enabled":true,"route-ids":["204000057"]}}
                """));
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            HttpResponse<String> response;
            JsonNode body;
            do {
                response = get(uri);
                body = JSON.readTree(response.body());
                if (body.path("status").asString().equals("READY")) break;
                Thread.sleep(100);
            } while (System.nanoTime() < deadline);
            assertThat(body.path("status").asString()).as(body.toString()).isEqualTo("READY");
            assertThat(body.path("points")).hasSize(836);
            assertThat(body.path("busStops")).hasSize(12);
            assertThat(body.at("/busStops/0/point/x").doubleValue()).isEqualTo(127);
            assertThat(body.at("/turningPoint/stopSequence").intValue()).isEqualTo(12);
            assertThat(body.path("coordinateSystem").asString()).isEqualTo("WGS84_ASSUMED");
            assertThat(runtime.source.geometryCalls()).isEqualTo(1);
            assertThat(runtime.source.locationCalls()).isZero();
            assertThat(runtime.routeReservations()).isEqualTo(3);
            String etag = response.headers().firstValue("ETag").orElseThrow();
            var unchanged = HTTP.send(HttpRequest.newBuilder(uri).header("If-None-Match", etag).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(unchanged.statusCode()).isEqualTo(304);
            assertThat(unchanged.body()).isEmpty();
            var compressed = HTTP.send(HttpRequest.newBuilder(uri).header("Accept-Encoding", "gzip").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
            assertThat(compressed.headers().firstValue("Content-Encoding")).contains("gzip");
            try (var gzip = new GZIPInputStream(new ByteArrayInputStream(compressed.body()))) {
                assertThat(JSON.readTree(gzip).path("geometryRevision").asString()).isEqualTo(body.path("geometryRevision").asString());
            }
            assertThat(runtime.source.geometryCalls()).isEqualTo(1);
            var eta = runtime.awaitValue("206000637", "/status", "READY", Duration.ofSeconds(8));
            assertThat(eta.body().path("routes")).hasSize(2);
            assertThat(runtime.reservations()).isEqualTo(1);
            assertThat(runtime.source.locationCalls()).isZero();
            java.nio.file.Files.writeString(java.nio.file.Path.of("build/reports/geometry-runtime-response.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(body));
            System.out.printf("geometry_runtime points=836 stops=12 sourceCalls=1 bytes=%d gzipBytes=%d%n",
                response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length, compressed.body().length);
        }
    }

    private static HttpResponse<String> get(URI uri) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
