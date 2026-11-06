package com.gustler.backend.routegeometry.engine.api.accuracy;

import com.gustler.backend.routegeometry.engine.api.RouteDisplayGeometry;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;

/** Canonical emitted geometry bytes; excludes revision, quality labels and proof metadata to avoid circular seals. */
public final class GeometryPayloadFingerprint {
    private GeometryPayloadFingerprint() {}
    public static String of(RouteDisplayGeometry geometry) {
        try {
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
            out.writeUTF("route-emitted-geometry-v1");out.writeBoolean(geometry.paths()!=null);
            if(geometry.paths()!=null) for(var path:java.util.List.of(geometry.paths().up(),geometry.paths().down())) {
                out.writeInt(path.points().size());for(var p:path.points()) point(out,p);
            }
            out.writeInt(geometry.connections().size());
            for(var row:new TreeMap<>(geometry.connections()).entrySet()) {
                out.writeInt(row.getKey());var c=row.getValue();out.writeDouble(c.point().x());out.writeDouble(c.point().y());
                out.writeInt(c.segmentIndex());out.writeDouble(c.segmentFraction());
            }
            out.writeInt(geometry.stationPositions().size());
            for(var row:new TreeMap<>(geometry.stationPositions()).entrySet()) { out.writeInt(row.getKey());point(out,row.getValue()); }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch(IOException|NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void point(DataOutputStream out,RouteGeometry.Point point) throws IOException {
        out.writeDouble(point.x());out.writeDouble(point.y());
    }
}
