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
import java.util.List;
import java.util.Map;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

public final class GbisContractCheck {
    public static void main(String[] args) {
        try {
            String mode = args.length == 1 ? args[0] : "normal";
            LocalData.require(List.of("normal", "empty", "unknown-seat", "upstream-error").contains(mode), "LOCAL_SCENARIO");
            LocalData.require("local-only-placeholder".equals(System.getenv("GBIS_SERVICE_KEY")), "LOCAL_KEY_ONLY");
            RouteDataset data = RouteDataset.read(Path.of("/local/data/routes.json"));
            var json = JsonMapper.builder().build();
            var caller = new GbisApiCaller(RestClient.builder().baseUrl("http://gbis-mock:8080").build(),
                new GbisClientOptions("http://gbis-mock:8080", "local-only-placeholder"), json);
            var catalog = new GbisRouteSource(new GbisRouteInfoSource(caller, json));
            var locations = new GbisLocationSource(caller, json);
            for (RouteDataset.Route route : data.routes()) {
                var read = catalog.read(route.routeId());
                LocalData.require(read instanceof RouteSourceResult.Success, "GBIS_ROUTE_CONTRACT");
                var actual = ((RouteSourceResult.Success) read).route();
                LocalData.require(actual.displayName().equals(route.displayName())
                    && RouteContentDigest.of(actual.stops()).value().equals(route.contentDigest())
                    && actual.stops().turnSequence().equals(route.turnSequence())
                    && actual.stops().stops().equals(route.domainStops().stops()), "GBIS_STATION_CONTRACT");
                var result = locations.read(route.routeId());
                String expected = route.displayName().equals("1650") ? mode : "normal";
                boolean valid = switch (expected) {
                    case "empty" -> result instanceof GbisLocationResult.NoVehicles;
                    case "upstream-error" -> result instanceof GbisLocationResult.GbisSystemError;
                    case "normal", "unknown-seat" -> result instanceof GbisLocationResult.Success success
                        && !success.buses().isEmpty() && success.buses().stream().allMatch(bus ->
                            bus.routeId().equals(route.routeId()) && bus.vehicleId().startsWith("L")
                            && bus.stopSequence() >= 1 && bus.stopSequence() <= route.stops().size()
                            && route.stops().get(bus.stopSequence() - 1).stationId().equals(bus.stopId())
                            && bus.remainingSeatCount() != null
                            && (expected.equals("unknown-seat") ? bus.remainingSeatCount() == -1 : bus.remainingSeatCount() >= 0));
                    default -> false;
                };
                LocalData.require(valid, "GBIS_LOCATION_CONTRACT");
            }
            System.out.println(json.writeValueAsString(Map.of("status", "passed", "routes", data.routes().size(),
                "stops", data.routes().stream().mapToInt(route -> route.stops().size()).sum(), "scenario", mode,
                "realGbisClientAndRouteMapper", true)));
        } catch (Exception error) {
            System.err.println("{\"status\":\"failed\",\"code\":\"GBIS_CONTRACT\"}");
            System.exit(1);
        }
    }
}
