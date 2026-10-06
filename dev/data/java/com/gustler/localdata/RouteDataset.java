package com.gustler.localdata;

import com.gustler.backend.forecasting.domain.model.ModelRoute;
import com.gustler.backend.routecatalog.domain.BoardingPolicy;
import com.gustler.backend.routecatalog.domain.RouteContentDigest;
import com.gustler.backend.routecatalog.domain.RouteStop;
import com.gustler.backend.routecatalog.domain.RouteStops;
import com.gustler.backend.routecatalog.domain.StopCoordinates;
import com.gustler.backend.routecatalog.domain.StopDirection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

record RouteDataset(String version, List<RouteDataset.Route> routes) {
    static final List<String> MODEL_ROUTES = List.of("1650", "3330", "9007", "9300", "6011", "3000", "5600", "3500");

    static RouteDataset read(Path path) throws Exception {
        LocalData.require(!Files.isSymbolicLink(path) && Files.size(path) < 1_048_576, "ROUTES_FILE");
        var mapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build();
        RouteDataset data = mapper.readValue(Files.readAllBytes(path), RouteDataset.class);
        LocalData.require("local-routes-v1".equals(data.version()), "ROUTES_VERSION");
        LocalData.require(data.routes().stream().map(Route::displayName).toList().equals(MODEL_ROUTES), "ROUTES_ORDER");
        for (Route route : data.routes()) {
            LocalData.require(ModelRoute.of(route.routeId()).equals(route.displayName()), "ROUTE_ID");
            LocalData.require(route.stops().size() >= 2 && route.stops().size() <= 512
                && route.turnSequence() > 1 && route.turnSequence() < route.stops().size(), "ROUTE_SIZE");
            for (int index = 0; index < route.stops().size(); index++) {
                Stop stop = route.stops().get(index);
                LocalData.require(stop.sequence() == index + 1 && stop.stationId().matches("[0-9]{9}")
                    && !stop.name().isBlank() && stop.name().length() <= 60, "STOP_IDENTITY");
                LocalData.require(stop.direction().equals(stop.sequence() <= route.turnSequence() ? "UP" : "DOWN")
                    && stop.boardingAllowed() == BoardingPolicy.allowsBoardingAt(stop.stationId()), "STOP_POLICY");
                LocalData.require(Double.isFinite(stop.x()) && Double.isFinite(stop.y())
                    && stop.x() >= -180 && stop.x() <= 180 && stop.y() >= -90 && stop.y() <= 90, "STOP_COORDINATES");
            }
            LocalData.require(RouteContentDigest.of(route.domainStops()).value().equals(route.contentDigest()), "ROUTE_DIGEST");
            for (String time : route.timetable().values()) {
                LocalData.require(time == null || time.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]"), "TIMETABLE");
            }
        }
        return data;
    }

    record Route(String routeId, String displayName, String startStopName, String endStopName,
                 int turnSequence, String contentDigest, Timetable timetable, List<Stop> stops) {
        RouteStops domainStops() {
            return new RouteStops(turnSequence, stops.stream().map(stop -> new RouteStop(
                stop.sequence(), stop.stationId(), stop.name(), StopDirection.valueOf(stop.direction()),
                stop.boardingAllowed(), new StopCoordinates(stop.x(), stop.y()))).toList());
        }
    }

    record Timetable(String upFirstDepartureTime, String upLastDepartureTime,
                     String downFirstDepartureTime, String downLastDepartureTime) {
        List<String> values() {
            return java.util.Arrays.asList(upFirstDepartureTime, upLastDepartureTime,
                downFirstDepartureTime, downLastDepartureTime);
        }
    }

    record Stop(int sequence, String stationId, String name, String direction,
                boolean boardingAllowed, double x, double y) { }
}
