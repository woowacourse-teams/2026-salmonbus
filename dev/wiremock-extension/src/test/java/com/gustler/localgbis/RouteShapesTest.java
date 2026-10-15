package com.gustler.localgbis;

import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.common.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RouteShapesTest {
    @TempDir Path directory;
    private ReplayEngine.Dataset dataset;
    private List<Map<String, Object>> shapes;
    private Path file;

    @BeforeEach
    void prepare() throws Exception {
        dataset = ReplayEngine.readAllRoutes(Path.of(System.getProperty("routes.file")));
        shapes = new ArrayList<>();
        for (var route : dataset.routes()) {
            Map<String, Object> shape = new LinkedHashMap<>();
            shape.put("routeId", route.routeId());
            shape.put("referenceDigest", route.contentDigest());
            shape.put("points", List.of(Map.of("x", 127.01, "y", 37.01), Map.of("x", 127.02, "y", 37.02)));
            shapes.add(shape);
        }
        file = directory.resolve("route-shapes.json");
    }

    private Map<String, List<RouteShapes.Point>> load() throws Exception {
        Files.writeString(file, Json.write(Map.of("version", "gbis-route-shapes-v1",
            "source", Map.of("projection", "EPSG:4326"), "routes", shapes)));
        return RouteShapes.read(file, dataset);
    }

    @Test
    void snapshotIsImmutableAndBoundToEveryCatalogReference() throws Exception {
        var loaded = load();
        assertEquals(dataset.routes().size(), loaded.size());
        assertEquals(new RouteShapes.Point(127.01, 37.01), loaded.get(dataset.routes().get(0).routeId()).get(0));
        assertThrows(UnsupportedOperationException.class, loaded::clear);
        assertThrows(UnsupportedOperationException.class,
            () -> loaded.get(dataset.routes().get(0).routeId()).clear());
    }

    @Test
    void missingDuplicateAndUnknownRoutesAreRejected() throws Exception {
        var first = shapes.remove(0);
        assertThrows(IllegalArgumentException.class, this::load);
        shapes.add(first);
        shapes.add(first);
        assertThrows(IllegalArgumentException.class, this::load);
        shapes.remove(shapes.size() - 1);
        first.put("routeId", "999999999");
        assertThrows(IllegalArgumentException.class, this::load);
    }

    @Test
    void catalogRevisionChangeNeedsANewMatchingShapeSnapshot() throws Exception {
        shapes.get(0).put("referenceDigest", "0".repeat(64));
        assertThrows(IllegalArgumentException.class, this::load);
        shapes.get(0).put("referenceDigest", dataset.routes().get(0).contentDigest());
        assertEquals(dataset.routes().size(), load().size());
    }

    @Test
    void catalogExpansionRequiresAndAcceptsTheNewRouteWithoutAnIdAllowlist() throws Exception {
        var template = dataset.routes().get(0);
        var added = new ReplayEngine.Route("999999999", "future-route", template.startStopName(),
            template.endStopName(), template.turnSequence(), template.contentDigest(), template.timetable(), template.stops());
        var expanded = new ArrayList<>(dataset.routes());
        expanded.add(added);
        dataset = new ReplayEngine.Dataset(dataset.version(), expanded);
        assertThrows(IllegalArgumentException.class, this::load);
        shapes.add(new LinkedHashMap<>(Map.of("routeId", added.routeId(), "referenceDigest", added.contentDigest(),
            "points", shapes.get(0).get("points"))));
        assertEquals(expanded.size(), load().size());
    }

    @Test
    void absentSingletonAndInvalidCoordinatesAreRejectedWithoutStopFallback() throws Exception {
        for (var invalid : List.of(List.of(), List.of(Map.of("x", 127, "y", 37)),
            List.of(Map.of("x", 181, "y", 37), Map.of("x", 127, "y", 37)),
            List.of(Map.of("x", 127, "y", -91), Map.of("x", 127, "y", 37)),
            List.of(Map.of("x", 0, "y", 37), Map.of("x", 127, "y", 37)),
            List.of(Map.of("x", 127, "y", 0), Map.of("x", 127, "y", 37)),
            List.of(Map.of("x", 127, "y", 37), Map.of("x", 127, "y", 37)),
            List.of(Map.of("x", "127", "y", 37), Map.of("x", 127, "y", 37)),
            List.of(Map.of("x", Double.NaN, "y", 37), Map.of("x", 127, "y", 37)),
            List.of(Map.of("x", Double.POSITIVE_INFINITY, "y", 37), Map.of("x", 127, "y", 37)))) {
            shapes.get(0).put("points", invalid);
            assertThrows(IllegalArgumentException.class, this::load);
        }
        shapes.get(0).remove("points");
        assertThrows(IllegalArgumentException.class, this::load);
    }

    @Test
    void absentSymlinkAndWrongVersionFilesAreRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> RouteShapes.read(file, dataset));
        load();
        Path link = directory.resolve("linked.json");
        Files.createSymbolicLink(link, file.getFileName());
        assertThrows(IllegalArgumentException.class, () -> RouteShapes.read(link, dataset));
        Files.writeString(file, Json.write(Map.of("version", "unsupported", "routes", shapes)));
        assertThrows(IllegalArgumentException.class, () -> RouteShapes.read(file, dataset));
    }
}
