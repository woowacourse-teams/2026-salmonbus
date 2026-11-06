package com.gustler.backend.routegeometry.engine.api;

import java.util.List;
import java.util.Objects;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.ProjCoordinate;

/**
 * Horizontal reference projection. All native X/Y inputs use easting/northing (or longitude/latitude),
 * regardless of the authority definition's axis order. Z/vertical datum transformations are not supported.
 * Native chainage is planar metres in the declared source CRS, never WGS84 or display-path distance.
 */
public final class NativeReferenceProjection {
    private NativeReferenceProjection() {}
    // Proj4J parses the EPSG resource when creating each CRS. Reuse only within a thread,
    // so independent worker searches do not share mutable projection objects.
    private static final ThreadLocal<java.util.Map<String,CoordinateTransform>> TRANSFORMS=
        ThreadLocal.withInitial(java.util.HashMap::new);
    private static CoordinateTransform transform(int from,int to) {
        return TRANSFORMS.get().computeIfAbsent(from+":"+to,key->{
            var factory=new CRSFactory();
            return new CoordinateTransformFactory().createTransform(factory.createFromName("EPSG:"+from),factory.createFromName("EPSG:"+to));
        });
    }

    public enum Datum { GRS80, WGS84 }
    public enum Unit { METRES, DEGREES }

    /** GRS80 identifies the supported horizontal ellipsoid; it does not assert a surveyed epoch. */
    public record Crs(String authority, int code, Datum datum, Unit unit) {
        public static final Crs EPSG_5179 = new Crs("EPSG", 5179, Datum.GRS80, Unit.METRES);
        public static final Crs EPSG_5186 = new Crs("EPSG", 5186, Datum.GRS80, Unit.METRES);
        // Pseudo-Mercator metres are web-map coordinates, never accepted as native ground-distance chainage.
        public static final Crs EPSG_3857 = new Crs("EPSG", 3857, Datum.WGS84, Unit.METRES);
        public static final Crs EPSG_4326 = new Crs("EPSG", 4326, Datum.WGS84, Unit.DEGREES);
        public Crs {
            boolean projected = (code == 5179 || code == 5186) && datum == Datum.GRS80 && unit == Unit.METRES;
            boolean webMercator = code == 3857 && datum == Datum.WGS84 && unit == Unit.METRES;
            boolean geographic = code == 4326 && datum == Datum.WGS84 && unit == Unit.DEGREES;
            if (!"EPSG".equals(authority) || !(projected || geographic || webMercator))
                throw new IllegalArgumentException("Unsupported CRS, horizontal ellipsoid or unit");
        }
    }

    public record NativePoint(double x, double y) {
        public NativePoint {
            if (!Double.isFinite(x) || !Double.isFinite(y))
                throw new IllegalArgumentException("Native coordinates must be finite");
        }
    }

    /** EPSG definitions come from proj4j-epsg; no fitted offsets or route-specific constants are used. */
    public static RouteGeometry.Point toWgs84(Crs crs, NativePoint point) {
        Objects.requireNonNull(crs, "crs");
        Objects.requireNonNull(point, "point");
        if (crs.code() == 4326) return new RouteGeometry.Point(point.x(), point.y());
        if (crs.code() == 3857) {
            // Bound native values before inverse projection; longitude wrapping must not hide invalid web coordinates.
            var extent = fromWgs84(crs, new RouteGeometry.Point(180, 85.06));
            if (Math.abs(point.x()) > extent.x() || Math.abs(point.y()) > extent.y())
                throw new IllegalArgumentException("Web Mercator coordinate is outside the supported EPSG area");
        }
        var transform = transform(crs.code(),4326);
        var result = new ProjCoordinate();
        transform.transform(new ProjCoordinate(point.x(), point.y()), result);
        var projected = new RouteGeometry.Point(result.x, result.y);
        requireAreaOfUse(crs, projected);
        return projected;
    }

    /** Inverse EPSG projection using the same explicit longitude/latitude and easting/northing convention. */
    public static NativePoint fromWgs84(Crs crs, RouteGeometry.Point point) {
        Objects.requireNonNull(crs, "crs");
        Objects.requireNonNull(point, "point");
        if (crs.code() == 4326) return new NativePoint(point.x(), point.y());
        requireAreaOfUse(crs, point);
        var transform = transform(4326,crs.code());
        var result = new ProjCoordinate();
        transform.transform(new ProjCoordinate(point.x(), point.y()), result);
        return new NativePoint(result.x, result.y);
    }

    private static void requireAreaOfUse(Crs crs, RouteGeometry.Point projected) {
        // EPSG area-of-use bounds, not a route-fit tolerance. Out-of-area inputs are unsupported.
        double west = crs.code() == 3857 ? -180 : crs.code() == 5179 ? 122.71 : 126.0;
        double south = crs.code() == 3857 ? -85.06 : crs.code() == 5179 ? 28.6 : 33.14;
        double east = crs.code() == 3857 ? 180 : crs.code() == 5179 ? 134.28 : 128.0;
        double north = crs.code() == 3857 ? 85.06 : crs.code() == 5179 ? 40.27 : 38.33;
        if (projected.x() < west || projected.x() > east || projected.y() < south || projected.y() > north)
            throw new IllegalArgumentException("Coordinate is outside the supported EPSG area; check X/Y order");
    }

    /** Interpolate on the native segment first; callers may project the resulting point afterward. */
    public static NativePoint nativePointAt(List<NativePoint> vertices, int segmentIndex, double fraction) {
        validateSegment(vertices, segmentIndex, fraction);
        var a = vertices.get(segmentIndex);
        var b = vertices.get(segmentIndex + 1);
        return new NativePoint(a.x() + (b.x() - a.x()) * fraction,
                a.y() + (b.y() - a.y()) * fraction);
    }

    public static double nativeChainage(Crs crs, List<NativePoint> vertices, int segmentIndex, double fraction) {
        requireMetres(crs);
        validateSegment(vertices, segmentIndex, fraction);
        double distance = 0;
        for (int i = 0; i < segmentIndex; i++) distance += length(vertices.get(i), vertices.get(i + 1));
        distance += length(vertices.get(segmentIndex), vertices.get(segmentIndex + 1)) * fraction;
        if (!Double.isFinite(distance)) throw new IllegalArgumentException("Native length overflow");
        return distance;
    }

    public static double nativeLength(Crs crs, List<NativePoint> vertices) {
        Objects.requireNonNull(vertices, "vertices");
        return nativeChainage(crs, vertices, vertices.size() - 2, 1);
    }

    private static void requireMetres(Crs crs) {
        if (crs == null || crs.unit() != Unit.METRES || (crs.code() != 5179 && crs.code() != 5186))
            throw new IllegalArgumentException("Native metre chainage requires EPSG:5179 or EPSG:5186; web-map metres and degrees are not native chainage");
    }

    private static void validateSegment(List<NativePoint> vertices, int segmentIndex, double fraction) {
        if (vertices == null || vertices.size() < 2 || segmentIndex < 0 || segmentIndex >= vertices.size() - 1
                || !Double.isFinite(fraction) || fraction < 0 || fraction > 1 || vertices.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("A valid native segment and fraction in [0,1] are required");
    }

    private static double length(NativePoint a, NativePoint b) {
        return Math.hypot(b.x() - a.x(), b.y() - a.y());
    }
}
