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
            var selections = new java.util.LinkedHashMap<String, String>();
            routes.forEach(route -> selections.put(route.routeId(),
                args[0].equals("showcase") ? SHOWCASE.get(route.displayName()) : args[0]));
            configure(client, "http://127.0.0.1:8080", selections);
            System.out.println(Json.write(Map.of("status", "ok", "mode", args[0], "routes", routes.size())));
        } catch (Exception error) {
            System.err.println("시나리오와 노선 선택 또는 WireMock 상태를 확인해 주세요.");
            System.exit(1);
        }
    }

    static void configure(HttpClient client, String baseUrl, Map<String, String> selections) throws Exception {
        var snapshot = client.send(HttpRequest.newBuilder(URI.create(baseUrl + "/__admin/mappings"))
            .timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString());
        if (snapshot.statusCode() != 200) throw new IllegalStateException();
        var modes = new java.util.TreeMap<String, String>();
        for (var mapping : Json.node(snapshot.body()).path("mappings")) {
            String routeId = mapping.path("request").path("queryParameters").path("routeId").path("equalTo").asText();
            var parameters = mapping.path("response").path("transformerParameters");
            if (mapping.path("id").asText().equals(scenarioId(routeId))
                && parameters.path("operation").asText().equals("location")) {
                modes.put(routeId, parameters.path("mode").asText());
            }
        }
        for (var selection : selections.entrySet()) {
            String routeId = selection.getKey();
            String mode = selection.getValue();
            if (!routeId.matches("[0-9]{9}") || !ReplayEngine.MODES.contains(mode)) {
                throw new IllegalArgumentException();
            }
            String id = scenarioId(routeId);
            if (mode.equals("normal")) {
                final int status = request(client, baseUrl + "/__admin/mappings/" + id, "DELETE", "");
                if (status != 200 && status != 404) throw new IllegalStateException();
                modes.remove(routeId);
            } else {
                Map<String, Object> response = new java.util.LinkedHashMap<>();
                response.put("status", 200);
                response.put("transformers", List.of("gbis-replay"));
                response.put("transformerParameters", Map.of("operation", "location", "mode", mode));
                if (mode.equals("timeout")) response.put("fixedDelayMilliseconds", 7_000);
                put(client, baseUrl, id, Map.of("id", id, "priority", 1, "request", Map.of(
                    "method", "GET", "urlPath", "/buslocationservice/v2/getBusLocationListv2",
                    "queryParameters", Map.of("routeId", Map.of("equalTo", routeId),
                        "serviceKey", Map.of("matches", "(local-only-placeholder|shared-dev-placeholder)"),
                        "format", Map.of("equalTo", "json"))), "response", response));
                modes.put(routeId, mode);
            }
        }
        // A station can serve several routes; retain each route's selected location scenario.
        String id = UUID.nameUUIDFromBytes("local-arrival-scenarios".getBytes(StandardCharsets.UTF_8)).toString();
        put(client, baseUrl, id, Map.of("id", id, "priority", 1, "request", Map.of(
            "method", "GET", "urlPath", "/busarrivalservice/v2/getBusArrivalListv2",
            "queryParameters", Map.of("stationId", Map.of("matches", "[0-9]{9}"),
                "serviceKey", Map.of("matches", "(local-only-placeholder|shared-dev-placeholder)"),
                "format", Map.of("equalTo", "json"))),
            "response", Map.of("status", 200, "transformers", List.of("gbis-replay"),
                "transformerParameters", Map.of("operation", "arrival", "routeModes", modes))));
    }

    private static String scenarioId(String routeId) {
        return UUID.nameUUIDFromBytes(("local-scenario-" + routeId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static void put(HttpClient client, String baseUrl, String id, Map<String, Object> mapping) throws Exception {
        String body = Json.write(mapping);
        int status = request(client, baseUrl + "/__admin/mappings/" + id, "PUT", body);
        if (status == 404) status = request(client, baseUrl + "/__admin/mappings", "POST", body);
        if (status < 200 || status >= 300) throw new IllegalStateException();
    }

    private static int request(HttpClient client, String uri, String method, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(3))
            .header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
