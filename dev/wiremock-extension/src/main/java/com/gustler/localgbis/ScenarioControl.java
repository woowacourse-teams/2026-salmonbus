package com.gustler.localgbis;

import com.github.tomakehurst.wiremock.common.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ScenarioControl {
    private static final Map<String, String> SHOWCASE = Map.of(
        "1650", "normal", "3330", "bunched", "9007", "mixed", "9300", "arriving",
        "6011", "departed", "3000", "unknown-seat", "5600", "empty", "3500", "upstream-error");

    public static void main(String[] args) {
        try {
            if (args.length == 1 && args[0].equals("list")) {
                System.out.println(Json.write(Map.of("modes", ReplayEngine.MODES, "showcase", SHOWCASE)));
                return;
            }
            if (args.length < 1 || args.length > 2
                || !ReplayEngine.MODES.contains(args[0]) && !args[0].equals("showcase")) {
                throw new IllegalArgumentException();
            }
            String selected = args.length == 2 ? args[1] : "all";
            if (args[0].equals("showcase") && !selected.equals("all")) {
                throw new IllegalArgumentException();
            }
            var data = ReplayEngine.readRoutes(Path.of("/local/data/routes.json"));
            var routes = data.routes().stream().filter(route -> selected.equals("all") || selected.equals(route.displayName())).toList();
            if (routes.isEmpty()) {
                throw new IllegalArgumentException();
            }
            var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            for (var route : routes) {
                String mode = args[0].equals("showcase") ? SHOWCASE.get(route.displayName()) : args[0];
                String id = UUID.nameUUIDFromBytes(("local-scenario-" + route.routeId()).getBytes(StandardCharsets.UTF_8)).toString();
                String uri = "http://127.0.0.1:8080/__admin/mappings/" + id;
                if (mode.equals("normal")) {
                    final int status = request(client, uri, "DELETE", "");
                    if (status != 200 && status != 404) {
                        throw new IllegalStateException();
                    }
                } else {
                    Map<String, Object> response = new java.util.LinkedHashMap<>();
                    response.put("status", 200);
                    response.put("transformers", List.of("gbis-replay"));
                    response.put("transformerParameters", Map.of("operation", "location", "mode", mode));
                    if (mode.equals("timeout")) { response.put("fixedDelayMilliseconds", 7_000); }
                    String mapping = Json.write(Map.of("id", id, "priority", 1, "request", Map.of(
                        "method", "GET", "urlPath", "/buslocationservice/v2/getBusLocationListv2",
                        "queryParameters", Map.of("routeId", Map.of("equalTo", route.routeId()),
                            "serviceKey", Map.of("equalTo", "local-only-placeholder"), "format", Map.of("equalTo", "json"))),
                        "response", response));
                    int status = request(client, uri, "PUT", mapping);
                    if (status == 404) {
                        status = request(client, "http://127.0.0.1:8080/__admin/mappings", "POST", mapping);
                    }
                    if (status < 200 || status >= 300) {
                        throw new IllegalStateException();
                    }
                }
            }
            System.out.println(Json.write(Map.of("status", "ok", "mode", args[0], "routes", routes.size())));
        } catch (Exception error) {
            System.err.println("시나리오와 노선 선택 또는 WireMock 상태를 확인해 주세요.");
            System.exit(1);
        }
    }

    private static int request(HttpClient client, String uri, String method, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(3))
            .header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
