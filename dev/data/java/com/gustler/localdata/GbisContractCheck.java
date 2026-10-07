package com.gustler.localdata;

import com.gustler.backend.gbis.api.GbisApiCaller;
import com.gustler.backend.gbis.api.GbisClientOptions;
import com.gustler.backend.gbis.api.GbisLocationResult;
import com.gustler.backend.gbis.api.GbisLocationSource;
import com.gustler.backend.gbis.api.GbisRouteInfoSource;
import com.gustler.backend.routecatalog.domain.RouteContentDigest;
import com.gustler.backend.routecatalog.domain.RouteSourceResult;
import com.gustler.backend.routecatalog.infrastructure.gbis.GbisRouteSource;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import tools.jackson.databind.json.JsonMapper;

public final class GbisContractCheck {
    public static void main(String[] args) {
        try {
            String mode = args.length == 1 ? args[0] : "normal";
            LocalData.require(List.of("normal", "mixed", "single", "sparse", "bunched", "up-only", "down-only",
                "arriving", "departed", "in-transit", "few-seats", "full", "unknown-seat", "missing-seat",
                "anonymous", "empty", "unreadable", "invalid-response", "upstream-error", "timeout")
                .contains(mode), "LOCAL_SCENARIO");
            LocalData.require("local-only-placeholder".equals(System.getenv("GBIS_SERVICE_KEY")), "LOCAL_KEY_ONLY");
            RouteDataset data = RouteDataset.read(Path.of("/local/data/routes.json"));
            RouteDataset catalogData = RouteDataset.readCatalog(Path.of("/local/data/catalog-routes.json"));
            var json = JsonMapper.builder().build();
            var requestFactory = new JdkClientHttpRequestFactory();
            requestFactory.setReadTimeout(Duration.ofSeconds(5));
            var caller = new GbisApiCaller(RestClient.builder().requestFactory(requestFactory).baseUrl("http://gbis-mock:8080").build(),
                new GbisClientOptions("http://gbis-mock:8080", "local-only-placeholder"), json);
            var catalog = new GbisRouteSource(new GbisRouteInfoSource(caller, json));
            var locations = new GbisLocationSource(caller, json);
            for (RouteDataset.Route route : java.util.stream.Stream.concat(data.routes().stream(), catalogData.routes().stream()).toList()) {
                var read = catalog.read(route.routeId());
                LocalData.require(read instanceof RouteSourceResult.Success, "GBIS_ROUTE_CONTRACT");
                var actual = ((RouteSourceResult.Success) read).route();
                LocalData.require(actual.displayName().equals(route.displayName())
                    && RouteContentDigest.of(actual.stops()).value().equals(route.contentDigest())
                    && actual.stops().turnSequence().equals(route.turnSequence())
                    && actual.stops().stops().equals(route.domainStops().stops()), "GBIS_STATION_CONTRACT");
                if (!RouteDataset.MODEL_ROUTES.contains(route.displayName())) {
                    continue;
                }
                var result = locations.read(route.routeId());
                String expected = route.displayName().equals("1650") ? mode : "normal";
                boolean valid = switch (expected) {
                    case "empty" -> result instanceof GbisLocationResult.NoVehicles;
                    case "upstream-error" -> result instanceof GbisLocationResult.GbisSystemError;
                    case "timeout" -> result instanceof GbisLocationResult.NoResponse;
                    case "invalid-response" -> result instanceof GbisLocationResult.UnreadableResponse;
                    default -> result instanceof GbisLocationResult.Success success
                        && !success.buses().isEmpty() && success.buses().stream().allMatch(bus ->
                            bus.routeId().equals(route.routeId())
                            && (expected.equals("anonymous") ? bus.vehicleId() == null : bus.vehicleId() != null && bus.vehicleId().startsWith("L"))
                            && bus.stopSequence() >= 1 && bus.stopSequence() <= route.stops().size()
                            && route.stops().get(bus.stopSequence() - 1).stationId().equals(bus.stopId())
                            && switch (expected) {
                                case "missing-seat" -> bus.remainingSeatCount() == null;
                                case "unknown-seat" -> Integer.valueOf(-1).equals(bus.remainingSeatCount());
                                case "full" -> Integer.valueOf(0).equals(bus.remainingSeatCount());
                                case "few-seats" -> Integer.valueOf(3).equals(bus.remainingSeatCount());
                                case "mixed" -> java.util.Arrays.asList(-1, 0, 3, 16, 32, null).contains(bus.remainingSeatCount());
                                default -> bus.remainingSeatCount() != null && bus.remainingSeatCount() >= 0;
                            });
                };
                LocalData.require(valid, "GBIS_LOCATION_CONTRACT");
            }
            System.out.println(json.writeValueAsString(Map.of("status", "passed", "routes", data.routes().size() + catalogData.routes().size(),
                "stops", data.routes().stream().mapToInt(route -> route.stops().size()).sum()
                    + catalogData.routes().stream().mapToInt(route -> route.stops().size()).sum(), "scenario", mode,
                "realGbisClientAndRouteMapper", true)));
        } catch (Exception error) {
            System.err.println("{\"status\":\"failed\",\"code\":\"GBIS_CONTRACT\"}");
            System.exit(1);
        }
    }
}
