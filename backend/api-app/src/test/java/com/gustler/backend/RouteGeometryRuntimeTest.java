package com.gustler.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.arrival.support.ArrivalRuntime;
import com.gustler.backend.arrival.support.RouteGeometryFixture;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.stream.Collectors;
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
    void 기본설정의_기존_8노선_643방문을_수집하고_노선별_형상과_방향과_회차를_보존한다() throws Exception {
        var catalogRows = JSON.readTree(Files.readString(Path.of(System.getProperty("geometry.routes.fixture")))).path("routes");
        var catalog = new java.util.HashMap<String, JsonNode>();
        catalogRows.forEach(route -> catalog.put(route.path("routeId").asString(), route));
        var fixtures = new ArrayList<RouteGeometryFixture>();
        try (var capture = getClass().getResourceAsStream("/gbis/route-geometry-eight-20261014.json")) {
            JSON.readTree(capture).path("routes").forEach(route -> fixtures.add(RouteGeometryFixture.fromCaptured(route)));
        }
        assertThat(fixtures).extracting(RouteGeometryFixture::name)
            .containsExactlyInAnyOrder("3330", "1650", "9007", "9300", "6011", "3000", "5600", "3500");

        try (var runtime = new ArrivalRuntime()) {
            runtime.source.enableGeometry(fixtures);
            // route-geometry.route-ids를 지정하지 않아 실제 Worker 기본 8노선의 상속도 검증한다.
            runtime.startWorker(false, Map.of("SPRING_APPLICATION_JSON", """
                {"collection":{"enabled":false},"route-geometry":{"enabled":true},"arrivals":{"enabled":false}}
                """));
            var versions = new HashSet<String>();
            var revisions = new HashSet<String>();
            var responses = new ArrayList<JsonNode>();
            int visits = 0;
            for (int routeIndex = 0; routeIndex < fixtures.size(); routeIndex++) {
                var route = fixtures.get(routeIndex);
                var expected = catalog.get(route.routeId());
                var uri = runtime.arrivalsUri().resolve("/api/v2/routes/" + route.routeId() + "/geometry");
                var response = awaitReady(uri);
                var body = JSON.readTree(response.body());
                assertThat(body.path("routeId").asString()).isEqualTo(route.routeId());
                assertThat(body.path("name").asString()).isEqualTo(route.name());
                assertThat(body.path("startPoint").asString()).isEqualTo(route.startPoint());
                assertThat(body.path("endPoint").asString()).isEqualTo(route.endPoint());
                assertThat(body.at("/turningPoint/stopSequence").intValue()).isEqualTo(route.turnSequence());
                assertThat(body.path("busStops")).hasSize(route.stops().size());
                assertThat(body.path("points")).isEqualTo(JSON.valueToTree(route.points()));
                var paths = body.path("paths");
                assertThat(paths.size()).as(route.name()).isEqualTo(2);
                assertThat(paths.has("status")).isFalse();
                assertThat(paths.has("reason")).isFalse();
                var up = paths.at("/UP/points");
                var down = paths.at("/DOWN/points");
                assertThat(up.size()).as(route.name()).isGreaterThanOrEqualTo(2);
                assertThat(down.size()).as(route.name()).isGreaterThanOrEqualTo(2);
                assertThat(up.get(0)).isEqualTo(body.path("points").get(0));
                assertThat(up.get(up.size() - 1)).isEqualTo(down.get(0));
                assertThat(down.get(down.size() - 1)).isEqualTo(body.path("points").get(route.points().size() - 1));
                // 이 저장본의 회차 경계는 모두 원본 선분 내부에 있으며, 두 경로에 한 번씩 추가된다.
                assertThat(up.size() + down.size()).isEqualTo(route.points().size() + 2);
                for (int stopIndex = 0; stopIndex < route.stops().size(); stopIndex++) {
                    var stop = expected.path("stops").get(stopIndex);
                    var actual = body.path("busStops").get(stopIndex);
                    assertThat(actual.path("stationId")).isEqualTo(stop.path("stationId"));
                    assertThat(actual.path("stopSequence")).isEqualTo(stop.path("sequence"));
                    assertThat(actual.path("name")).isEqualTo(stop.path("name"));
                    assertThat(actual.path("direction")).isEqualTo(stop.path("direction"));
                    assertThat(actual.path("boardingAllowed")).isEqualTo(stop.path("boardingAllowed"));
                    assertThat(actual.at("/point/x")).isEqualTo(stop.path("x"));
                    assertThat(actual.at("/point/y")).isEqualTo(stop.path("y"));
                }
                if (route.name().equals("3500")) {
                    assertThat(route.stops().stream().map(RouteGeometryFixture.Stop::stationId).distinct().count())
                        .as("3500의 중복 정류장 방문을 합치지 않는다").isEqualTo(route.stops().size() - 1);
                }
                versions.add(body.path("referenceVersionId").asString());
                revisions.add(body.path("geometryRevision").asString());
                responses.add(body);
                visits += body.path("busStops").size();
                var pinned = get(URI.create(uri + "?referenceVersionId=" + body.path("referenceVersionId").asString()));
                assertThat(pinned.statusCode()).isEqualTo(200);
                assertThat(JSON.readTree(pinned.body())).isEqualTo(body);
                assertThat(response.headers().firstValue("ETag")).isPresent();
                assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("max-age=300");
            }
            assertThat(visits).isEqualTo(643);
            assertThat(versions).hasSize(8);
            assertThat(revisions).hasSize(8);
            assertThat(runtime.source.geometryCallsByRoute()).isEqualTo(fixtures.stream()
                .collect(Collectors.toMap(RouteGeometryFixture::routeId, ignored -> 1)));
            assertThat(runtime.routeReservations()).isEqualTo(24);
            assertThat(runtime.reservations()).isZero();
            assertThat(runtime.source.locationCalls()).isZero();
            Files.writeString(Path.of("build/reports/geometry-eight-runtime-responses.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(responses));
            assertThat(runtime.source.unexpected()).isEmpty();
            assertThat(fixtures.stream().mapToInt(route -> route.points().size()).sum()).isEqualTo(7063);
            System.out.println("geometry_runtime routes=8 visits=643 points=7063 routeQuota=24 arrivalQuota=0 liveGbisCalls=0 shapes=captured_20261014");
        }
    }

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
            // 합성 자료는 회차가 마지막 방문이라 복귀 경로의 근거가 없다. 전체 형상은 유지한다.
            assertThat(body.path("paths").isNull()).isTrue();
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

    private static HttpResponse<String> awaitReady(URI uri) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        HttpResponse<String> response;
        do {
            response = get(uri);
            if (response.statusCode() == 200 && JSON.readTree(response.body()).path("status").asString().equals("READY")) {
                return response;
            }
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("형상 수집 시간 초과: " + uri + " " + response.body());
    }
}
