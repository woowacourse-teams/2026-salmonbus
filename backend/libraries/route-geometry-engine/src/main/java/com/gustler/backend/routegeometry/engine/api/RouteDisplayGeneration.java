package com.gustler.backend.routegeometry.engine.api;

import com.gustler.backend.routegeometry.engine.api.RouteGeometry;
import com.gustler.backend.routegeometry.api.RouteDisplayInput;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Exact source, reference state and algorithm/configuration identity shared by worker and reader. */
public final class RouteDisplayGeneration {
    public static final String SOURCE_REVISION = AutomaticRouteDisplay.REVISION + "/source-only";
    private RouteDisplayGeneration() {}
    public static String key(String inputDigest, String evidenceRevision, String algorithmRevision) {
        return sha256(inputDigest + "\n" + evidenceRevision + "\n" + algorithmRevision);
    }
    /** Receipt refreshes do not change semantic identity; capture availability changes it once. */
    public static String key(String inputDigest,String evidenceRevision,String algorithmRevision,String sourceManifestId) {
        return sourceManifestId==null ? key(inputDigest,evidenceRevision,algorithmRevision)
            : sha256(inputDigest+"\n"+evidenceRevision+"\n"+algorithmRevision+"\nCAPTURED_INPUT_V1");
    }
    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static RouteDisplayInput input(RouteGeometry geometry) {
        return new RouteDisplayInput(geometry.routeId(), geometry.turnSequence(),
            geometry.points().stream().map(p -> new RouteDisplayInput.Point(p.x(), p.y())).toList(),
            geometry.busStops().stream().map(s -> new RouteDisplayInput.Visit(s.stationId(),
                s.stopSequence(), s.direction(), s.boardingAllowed(), s.point() == null ? null :
                new RouteDisplayInput.Point(s.point().x(), s.point().y()))).toList());
    }
    public static boolean roleEvidenceNeedsRefresh(RouteGeometry geometry) {
        if (geometry.shape() == null || geometry.shape().evidence().sources().stream()
                .noneMatch(s -> s.id().equals("GBIS_WEB") && s.status().equals("AVAILABLE"))) return false;
        return EvidenceRoutePaths.classify(geometry).classifications().isEmpty();
    }

    public static RouteGeometry at(RouteGeometry g, java.time.Instant now) {
        var s = g.shape();
        return new RouteGeometry(g.routeId(),g.referenceVersionId(),g.name(),g.startPoint(),g.endPoint(),
            g.turnSequence(),g.busStops(),new RouteGeometry.Shape(s.id(),s.receivedAt(),s.points(),s.evidence().at(now),s.sourceManifestId(),now,s.unboundSourceManifestId()));
    }
}
