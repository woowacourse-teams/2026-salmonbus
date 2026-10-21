package com.gustler.localgbis;

import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.common.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReplayHeadwaysTest {
    private final Path routeFile = Path.of(System.getProperty("routes.file"));

    @Test
    void routeInfoCarriesTheCapturedHeadwayFieldsOfEachRoute() throws Exception {
        var data = ReplayEngine.readAllRoutes(routeFile);
        var headways = ReplayEngine.readHeadways(routeFile.resolveSibling("route-headways.json"), data);
        var engine = new ReplayEngine(routeFile, routeFile.getParent().getParent().resolve("scenarios/replay.json"));

        assertEquals(data.routes().size(), headways.size());
        for (var route : data.routes()) {
            Map<?, ?> item = (Map<?, ?>) body(engine.response("route-info", "normal", route.routeId())).get("busRouteInfoItem");
            for (String field : ReplayEngine.HEADWAY_FIELDS) {
                assertEquals(headways.get(route.routeId()).get(field), item.get(field), route.displayName() + " " + field);
            }
            assertEquals(route.timetable().upFirstDepartureTime(), item.get("upFirstTime"), route.displayName());
        }
        Map<?, ?> route3330 = (Map<?, ?>) body(engine.response("route-info", "normal", "204000057")).get("busRouteInfoItem");
        assertEquals(5, route3330.get("peekAlloc"));
        assertEquals(15, route3330.get("nPeekAlloc"));
    }

    @Test
    void headwayFixtureIsAGbisCaptureWithAllEightIntegerOrNullFieldsAndResponseRecordForEveryRoute() throws Exception {
        var data = ReplayEngine.readAllRoutes(routeFile);
        var file = routeFile.resolveSibling("route-headways.json");
        var headways = ReplayEngine.readHeadways(file, data);
        var dataset = Json.read(Files.readString(file), ReplayEngine.HeadwayDataset.class);

        assertEquals("GBIS_OPENAPI_CAPTURE", dataset.source());
        assertEquals("busrouteservice/v2/getBusRouteInfoItemv2", dataset.operation());
        for (var route : data.routes()) {
            var fields = headways.get(route.routeId());
            assertEquals(ReplayEngine.HEADWAY_FIELDS.size(), fields.size(), route.displayName());
            assertTrue(fields.values().stream().allMatch(value -> value == null || value instanceof Integer), route.displayName());
            assertTrue(dataset.responses().get(route.routeId()).bodyBytes() > 0, route.displayName());
        }
    }

    @Test
    void replaysACapturedNullHeadwayAsNullAndKeepsTheOtherFields(@TempDir Path directory) throws Exception {
        for (String name : new String[]{"routes.json", "catalog-routes.json", "route-shapes.json"}) {
            Files.copy(routeFile.resolveSibling(name), directory.resolve(name));
        }
        Files.writeString(directory.resolve("route-headways.json"), """
            {"version":"local-route-headways-v1","source":"GBIS_OPENAPI_CAPTURE",
             "responses":{"204000057":{"queryTime":"2026-10-19 16:51:55.677","bodySha256":"%s","bodyBytes":1}},
             "routes":{"204000057":{"peekAlloc":5,"nPeekAlloc":15,"wePeekAlloc":null,"weNPeekAlloc":null}}}"""
            .formatted("0".repeat(64)));

        var engine = new ReplayEngine(directory.resolve("routes.json"),
            routeFile.getParent().getParent().resolve("scenarios/replay.json"));

        Map<?, ?> item = (Map<?, ?>) body(engine.response("route-info", "normal", "204000057")).get("busRouteInfoItem");
        assertEquals(5, item.get("peekAlloc"));
        assertEquals(15, item.get("nPeekAlloc"));
        assertTrue(item.containsKey("wePeekAlloc"));
        assertNull(item.get("wePeekAlloc"));
        assertNull(item.get("weNPeekAlloc"));
    }

    @Test
    void rejectsHeadwayFilesThatAreNotCapturesOrNameUnknownRoutesFieldsValueTypesOrMissResponseRecords(@TempDir Path directory) throws Exception {
        var data = ReplayEngine.readAllRoutes(routeFile);
        Path synthetic = directory.resolve("synthetic.json");
        Files.writeString(synthetic, """
            {"version":"local-route-headways-v1","source":"SYNTHETIC_DEV_FIXTURE","responses":{},"routes":{}}""");
        Path unknownRoute = directory.resolve("unknown-route.json");
        Files.writeString(unknownRoute, """
            {"version":"local-route-headways-v1","source":"GBIS_OPENAPI_CAPTURE","responses":{},"routes":{"999999999":{}}}""");
        Path unknownField = directory.resolve("unknown-field.json");
        Files.writeString(unknownField, """
            {"version":"local-route-headways-v1","source":"GBIS_OPENAPI_CAPTURE",
             "responses":{"204000057":{"queryTime":"2026-10-19 16:51:55.677","bodySha256":"%s","bodyBytes":1}},
             "routes":{"204000057":{"intervalTime":10}}}""".formatted("0".repeat(64)));
        Path booleanValue = directory.resolve("boolean-value.json");
        Files.writeString(booleanValue, """
            {"version":"local-route-headways-v1","source":"GBIS_OPENAPI_CAPTURE",
             "responses":{"204000057":{"queryTime":"2026-10-19 16:51:55.677","bodySha256":"%s","bodyBytes":1}},
             "routes":{"204000057":{"peekAlloc":true}}}""".formatted("0".repeat(64)));
        Path missingResponse = directory.resolve("missing-response.json");
        Files.writeString(missingResponse, """
            {"version":"local-route-headways-v1","source":"GBIS_OPENAPI_CAPTURE","responses":{},
             "routes":{"204000057":{"peekAlloc":5}}}""");

        assertThrows(IllegalArgumentException.class, () -> ReplayEngine.readHeadways(synthetic, data));
        assertThrows(IllegalArgumentException.class, () -> ReplayEngine.readHeadways(unknownRoute, data));
        assertThrows(IllegalArgumentException.class, () -> ReplayEngine.readHeadways(unknownField, data));
        assertThrows(IllegalArgumentException.class, () -> ReplayEngine.readHeadways(missingResponse, data));
        assertThrows(IllegalArgumentException.class, () -> ReplayEngine.readHeadways(booleanValue, data));
    }

    private static Map<?, ?> body(ReplayEngine.Reply reply) {
        return (Map<?, ?>) ((Map<?, ?>) reply.body().get("response")).get("msgBody");
    }
}
