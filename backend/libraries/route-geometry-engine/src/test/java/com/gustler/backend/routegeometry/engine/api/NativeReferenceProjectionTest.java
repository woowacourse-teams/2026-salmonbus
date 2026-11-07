package com.gustler.backend.routegeometry.engine.api;

import static org.assertj.core.api.Assertions.*;
import static com.gustler.backend.routegeometry.engine.api.NativeReferenceProjection.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class NativeReferenceProjectionTest {
    private List<String[]> rows(String file) throws Exception {
        try (var reader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(
                getClass().getResourceAsStream("/native-reference/" + file)), StandardCharsets.UTF_8))) {
            return reader.lines().skip(1).map(line -> line.split(",")).toList();
        }
    }
    private Crs crs(String code) {
        return new Crs("EPSG", Integer.parseInt(code), Datum.GRS80, Unit.METRES);
    }
    private List<NativePoint> vertices(String code, String id) throws Exception {
        return rows("vertices.csv").stream().filter(row -> row[0].equals(code) && row[1].equals(id))
                .map(row -> new NativePoint(Double.parseDouble(row[3]), Double.parseDouble(row[4]))).toList();
    }

    @Test
    void officialProjectionMatchesIndependentPyprojOnArchivedNativeVertices() throws Exception {
        var golden = rows("vertices.csv");
        assertThat(golden).hasSize(16);
        for (var row : golden) {
            var projected = toWgs84(crs(row[0]), new NativePoint(Double.parseDouble(row[3]), Double.parseDouble(row[4])));
            assertThat(projected.x()).as("%s/%s vertex %s longitude", row[0], row[1], row[2])
                    .isCloseTo(Double.parseDouble(row[5]), within(1e-8));
            assertThat(projected.y()).isCloseTo(Double.parseDouble(row[6]), within(1e-8));
        }
    }

    @Test
    void inverseProjectionMatchesIndependentNativeGoldenRatherThanOnlyRoundTrips() throws Exception {
        for (var row : rows("vertices.csv")) {
            var nativePoint = fromWgs84(crs(row[0]), new RouteGeometry.Point(
                    Double.parseDouble(row[5]), Double.parseDouble(row[6])));
            // Numerical projection tolerance only; this does not express surveyed/map accuracy.
            assertThat(nativePoint.x()).isCloseTo(Double.parseDouble(row[3]), within(0.001));
            assertThat(nativePoint.y()).isCloseTo(Double.parseDouble(row[4]), within(0.001));
        }
    }

    @Test
    void inverseProjectionChecksAreaAndPreservesExplicitWgs84Identity() {
        assertThat(fromWgs84(Crs.EPSG_4326, new RouteGeometry.Point(127.015, 37.491)))
                .isEqualTo(new NativePoint(127.015, 37.491));
        assertThatThrownBy(() -> fromWgs84(Crs.EPSG_5179, new RouteGeometry.Point(-73, 40)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fromWgs84(Crs.EPSG_5186, new RouteGeometry.Point(129, 37)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fractionsAndChainageStayOnTheNativeMetreAxisBeforeProjection() throws Exception {
        var golden = rows("segments.csv");
        assertThat(golden).hasSize(12);
        for (var row : golden) {
            var vertices = vertices(row[0], row[1]);
            int segment = Integer.parseInt(row[2]);
            double fraction = Double.parseDouble(row[3]);
            var projected = toWgs84(crs(row[0]), nativePointAt(vertices, segment, fraction));
            assertThat(projected.x()).isCloseTo(Double.parseDouble(row[4]), within(1e-8));
            assertThat(projected.y()).isCloseTo(Double.parseDouble(row[5]), within(1e-8));
            assertThat(nativeChainage(crs(row[0]), vertices, segment, fraction))
                    .isCloseTo(Double.parseDouble(row[6]), within(1e-8));
            assertThat(nativeLength(crs(row[0]), vertices)).isCloseTo(Double.parseDouble(row[7]), within(1e-8));
        }
    }

    @Test
    void unknownCrsEllipsoidAndUnitCombinationsAreRejected() {
        assertThatThrownBy(() -> new Crs("UNSPECIFIED", 5179, Datum.GRS80, Unit.METRES)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Crs("EPSG", 3857, Datum.GRS80, Unit.METRES)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Crs("EPSG", 5186, Datum.WGS84, Unit.METRES)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Crs("EPSG", 5179, Datum.GRS80, Unit.DEGREES)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Crs("EPSG", 4326, Datum.WGS84, Unit.METRES)).isInstanceOf(IllegalArgumentException.class);
        var degrees = List.of(new NativePoint(127, 37), new NativePoint(127.001, 37.001));
        assertThatThrownBy(() -> nativeLength(Crs.EPSG_4326, degrees)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> nativeChainage(Crs.EPSG_4326, degrees, 0, .5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void archivedEastingNorthingAndLongitudeLatitudeSwapsAreNotSilentlyAccepted() throws Exception {
        for (var code : List.of("5179", "5186")) {
            var point = vertices(code, "A223AD534276").getFirst();
            assertThatThrownBy(() -> toWgs84(crs(code), new NativePoint(point.y(), point.x())))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> toWgs84(Crs.EPSG_4326, new NativePoint(37, 127)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidCoordinatesSegmentsAndFractionsCannotProduceAChainage() throws Exception {
        var vertices = vertices("5179", "A223AD534276");
        assertThatThrownBy(() -> new NativePoint(Double.NaN, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NativePoint(1, Double.POSITIVE_INFINITY)).isInstanceOf(IllegalArgumentException.class);
        for (double fraction : new double[] {-0.001, 1.001, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThatThrownBy(() -> nativePointAt(vertices, 0, fraction)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> nativeChainage(Crs.EPSG_5179, vertices, 0, fraction)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> nativePointAt(vertices, -1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> nativePointAt(vertices, vertices.size() - 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> nativeLength(Crs.EPSG_5179, List.of(vertices.getFirst()))).isInstanceOf(IllegalArgumentException.class);
        assertThat(nativePointAt(vertices, 0, 0)).isEqualTo(vertices.getFirst());
        assertThat(nativePointAt(vertices, 0, 1)).isEqualTo(vertices.get(1));
    }

    @Test
    void archivedGbisWebMercatorRowsMatchIndependentPyprojInBothDirections() throws Exception {
        var golden = rows("web-mercator.csv");
        assertThat(golden).hasSize(5);
        for (var row : golden) {
            var raw = new NativePoint(Double.parseDouble(row[2]), Double.parseDouble(row[3]));
            var expected = new RouteGeometry.Point(Double.parseDouble(row[4]), Double.parseDouble(row[5]));
            var projected = toWgs84(Crs.EPSG_3857, raw);
            assertThat(projected.x()).as("GBIS seq %s longitude", row[0]).isCloseTo(expected.x(), within(1e-8));
            assertThat(projected.y()).as("GBIS seq %s latitude", row[0]).isCloseTo(expected.y(), within(1e-8));
            var inverse = fromWgs84(Crs.EPSG_3857, expected);
            assertThat(inverse.x()).isCloseTo(raw.x(), within(0.001));
            assertThat(inverse.y()).isCloseTo(raw.y(), within(0.001));
        }
    }

    @Test
    void webMercatorMetresCannotBeUsedAsNativeLaneChainage() throws Exception {
        var web = rows("web-mercator.csv").stream()
                .map(row -> new NativePoint(Double.parseDouble(row[2]), Double.parseDouble(row[3]))).toList();
        assertThatThrownBy(() -> nativeLength(Crs.EPSG_3857, web)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> nativeChainage(Crs.EPSG_3857, web, 1, .5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Crs("EPSG", 3857, Datum.WGS84, Unit.DEGREES))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void webMercatorRejectsUnsupportedLatitudesAndOutOfRangeCoordinatesBeforeWrapping() {
        assertThatCode(() -> fromWgs84(Crs.EPSG_3857, new RouteGeometry.Point(127, 85.06)))
                .doesNotThrowAnyException();
        for (double latitude : new double[] {85.061, -85.061, 90, -90}) {
            assertThatThrownBy(() -> fromWgs84(Crs.EPSG_3857, new RouteGeometry.Point(127, latitude)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> toWgs84(Crs.EPSG_3857, new NativePoint(30_000_000, 4_500_000)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> toWgs84(Crs.EPSG_3857, new NativePoint(14_000_000, 30_000_000)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void explicitWgs84IdentityRetainsLongitudeLatitudeWithoutInventingMetres() {
        var point = toWgs84(Crs.EPSG_4326, new NativePoint(127.015, 37.491));
        assertThat(point).isEqualTo(new RouteGeometry.Point(127.015, 37.491));
    }
    @Test
    void thread_local_transforms_preserve_independent_goldens_when_crs_and_directions_interleave() throws Exception {
        var nativeRows=rows("vertices.csv");var webRows=rows("web-mercator.csv");
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var tasks=new java.util.ArrayList<java.util.concurrent.Callable<Void>>();
            for(int task=0;task<8;task++)tasks.add(()->{
                for(int repeat=0;repeat<3;repeat++) {
                    for(var row:nativeRows) {
                        var frame=crs(row[0]);var ll=new RouteGeometry.Point(Double.parseDouble(row[5]),Double.parseDouble(row[6]));
                        var xy=new NativePoint(Double.parseDouble(row[3]),Double.parseDouble(row[4]));
                        assertThat(toWgs84(frame,xy).x()).isCloseTo(ll.x(),within(1e-8));
                        assertThat(toWgs84(frame,xy).y()).isCloseTo(ll.y(),within(1e-8));
                        assertThat(fromWgs84(frame,ll).x()).isCloseTo(xy.x(),within(.001));
                        assertThat(fromWgs84(frame,ll).y()).isCloseTo(xy.y(),within(.001));
                    }
                    for(var row:webRows) {
                        var xy=new NativePoint(Double.parseDouble(row[2]),Double.parseDouble(row[3]));
                        assertThat(toWgs84(Crs.EPSG_3857,xy).x()).isCloseTo(Double.parseDouble(row[4]),within(1e-8));
                        assertThat(toWgs84(Crs.EPSG_3857,xy).y()).isCloseTo(Double.parseDouble(row[5]),within(1e-8));
                    }
                }
                return null;
            });
            for(var result:pool.invokeAll(tasks))result.get();
        }
    }

}
