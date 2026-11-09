package com.gustler.backend.worker.routegeometry.reference;

import static com.gustler.backend.worker.routegeometry.reference.ReferenceJson.*;
import com.gustler.backend.routegeometry.api.RouteSourceReceipt;
import com.gustler.backend.routegeometry.engine.api.NativeReferenceProjection;
import com.gustler.backend.routegeometry.engine.api.NativeReferenceProjection.Crs;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry;
import com.gustler.backend.worker.routegeometry.reference.PinnedRouteSearchGraph.XY;
import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Source-only CRS preprocessing, independently checked against Proj4J before any candidate search. */
final class CanonicalSourceObservations {
    private static final Map<String,String> FRAME_WKT_SHA=Map.of(
        "EPSG:5179","abd82c4d2ecad2448847227268538b5551b6f5959feec4e241a432661ac1d7c0",
        "EPSG:5186","7e85f053cb82a74a4189f8eb099566eb37fe1a8ebbb043ff9738d2b2ae8f352b");
    private static final ObjectMapper JSON=new ObjectMapper();
    private record Observation(RouteGeometry.Point source,XY point) {}
    private record Visit(String stationId,int sequence,String direction,Observation observation) {}
    private record Frame(List<Observation> points,Map<Integer,Visit> visits) {
        Frame {points=List.copyOf(points);visits=Map.copyOf(visits);}
    }
    private final Map<Integer,Frame> frames;
    private final Set<String> inputDigests;
    private CanonicalSourceObservations(Map<Integer,Frame> frames,Set<String> inputDigests) {
        this.frames=Map.copyOf(frames);this.inputDigests=Set.copyOf(inputDigests);
    }
    static CanonicalSourceObservations load(JsonNode root,JsonNode raw,String rawSha) {
        var c=root.path("canonicalObservations");
        require("canonical-source-observation-crs-v1".equals(text(c,"format"))
            && "CANONICAL_ALL_RAW_OBSERVATIONS_PYPROJ_V1".equals(text(c,"revision")),"UNSUPPORTED_CANONICAL_OBSERVATIONS");
        require(rawSha.equals(text(c,"rawSha256")) && text(raw,"routeId").equals(text(c,"routeId")),"CANONICAL_SOURCE_BINDING_MISMATCH");
        require("WGS84_ASSUMED".equals(text(c,"sourceCoordinateSystem"))
            && text(c,"sourceCoordinateSystem").equals(text(raw,"coordinateSystem")),"CANONICAL_SOURCE_CRS_MISMATCH");
        require("EVERY_ORIGINAL_RAW_POINT_AND_ORDERED_STATION_VISIT".equals(text(c,"coverage")),"INCOMPLETE_CANONICAL_SCOPE");
        for(String key:List.of("selectedPathRead","groundTruthRead","nativeGeometryModified"))
            require(c.path(key).isBoolean() && !c.path(key).booleanValue(),"CANONICAL_OBSERVATIONS_MUST_BE_SOURCE_ONLY");
        var policy=c.path("numericVerificationPolicy");
        require(policy.path("independentJavaProjectionRequired").isBoolean() && policy.path("independentJavaProjectionRequired").booleanValue()
            && number(policy.path("maximumEuclideanDifferenceMeters"))==.001,"UNSUPPORTED_CANONICAL_VERIFICATION_POLICY");
        Set<String> hashes=new HashSet<>();String exporter=hash(c.path("producer"),"sha256");
        require(exporter.equals(text(root.path("producer"),"canonicalObservationsExporterSha256"))
            && text(c,"revision").equals(text(root.path("producer"),"canonicalObservationsRevision")),"CANONICAL_PRODUCER_BINDING_MISMATCH");
        hashes.add(exporter);var environment=c.path("environment");
        for(String field:List.of("pyprojVersion","projVersion","epsgDatabaseVersion"))
            require(text(environment,field).matches("[0-9A-Za-z][0-9A-Za-z._+-]{0,64}"),"CANONICAL_TRANSFORM_PROVENANCE_REQUIRED");
        hashes.add(hash(environment.path("projDatabase"),"sha256"));
        String content=hash(c,"contentSha256");var unsigned=(ObjectNode)c.deepCopy();unsigned.remove("contentSha256");
        require(content.equals(canonicalSha(unsigned)),"CANONICAL_CONTENT_HASH_MISMATCH");hashes.add(content);
        require(c.path("frames").isArray() && c.path("frames").size()==2,"CANONICAL_FRAMES_REQUIRED");
        var sourcePoints=raw.path("points");var sourceVisits=raw.path("busStops");
        require(sourcePoints.isArray() && !sourcePoints.isEmpty() && sourceVisits.isArray(),"INVALID_CANONICAL_SOURCE");
        Map<Integer,Frame> frames=new HashMap<>();
        for(var frame:c.path("frames")) {
            String authority=text(frame,"crsAuthority"),wkt=text(frame,"crsWkt"),wktSha=hash(frame,"crsWktSha256");
            require(wktSha.equals(FRAME_WKT_SHA.get(authority)) && wktSha.equals(RouteSourceReceipt.sha256(wkt.getBytes(StandardCharsets.UTF_8))),"UNSUPPORTED_CANONICAL_FRAME_DEFINITION");
            require("EASTING_NORTHING_ALWAYS_XY".equals(text(frame,"axisOrder")) && "METRE".equals(text(frame,"horizontalUnit"))
                && "EPSG:4326".equals(text(frame,"transformInput")),"UNSUPPORTED_CANONICAL_FRAME_AXES_OR_UNIT");
            number(frame.path("declaredTransformAccuracyMeters"));text(frame,"transformerDescription");text(frame,"transformerDefinition");
            var ps=frame.path("sourcePoints");var vs=frame.path("sourceVisits");
            require(ps.isArray() && ps.size()==sourcePoints.size() && vs.isArray() && vs.size()==sourceVisits.size(),"INCOMPLETE_CANONICAL_OBSERVATIONS");
            var coordinateDocument=JSON.createObjectNode();coordinateDocument.set("sourcePoints",ps);coordinateDocument.set("sourceVisits",vs);
            String coordinates=hash(frame,"coordinatesSha256");require(coordinates.equals(canonicalSha(coordinateDocument)),"CANONICAL_COORDINATES_HASH_MISMATCH");hashes.add(coordinates);
            Crs crs=PinnedNativeCatalog.crs(authority);List<Observation> points=new ArrayList<>();Map<Integer,Visit> visits=new HashMap<>();
            for(int i=0;i<ps.size();i++) {
                var row=ps.get(i);require(integer(row.path("sourcePointIndex"))==i,"CANONICAL_POINT_ORDER_MISMATCH");
                points.add(observation(row,sourcePoints.get(i),crs));
            }
            for(int i=0;i<vs.size();i++) {
                var row=vs.get(i);var actual=sourceVisits.get(i);String id=text(actual,"stationId"),direction=text(actual,"direction");int sequence=integer(actual.path("stopSequence"));
                require(id.equals(text(row,"stationId")) && direction.equals(text(row,"direction"))
                    && sequence==integer(row.path("sequence")),"CANONICAL_VISIT_IDENTITY_MISMATCH");
                require(visits.put(sequence,new Visit(id,sequence,direction,observation(row,actual.path("point"),crs)))==null,"DUPLICATE_CANONICAL_VISIT");
            }
            require(frames.put(crs.code(),new Frame(points,visits))==null,"DUPLICATE_CANONICAL_FRAME");
        }
        require(frames.keySet().equals(Set.of(5179,5186)),"CANONICAL_FRAMES_REQUIRED");
        return new CanonicalSourceObservations(frames,hashes);
    }
    Set<String> inputDigests(){return inputDigests;}
    XY sourcePoint(Crs crs,int index,RouteGeometry.Point current) {
        var frame=frames.get(crs.code());require(frame!=null && index>=0 && index<frame.points().size(),"CANONICAL_SOURCE_POINT_UNAVAILABLE");
        var value=frame.points().get(index);require(Objects.equals(current,value.source()),"CANONICAL_CURRENT_SOURCE_MISMATCH");return value.point();
    }
    XY sourceVisit(Crs crs,RouteGeometry.Stop current) {
        var frame=frames.get(crs.code());require(frame!=null,"CANONICAL_SOURCE_FRAME_UNAVAILABLE");var visit=frame.visits().get(current.stopSequence());
        require(visit!=null && current.stationId().equals(visit.stationId()) && current.direction().equals(visit.direction())
            && Objects.equals(current.point(),visit.observation().source()),"CANONICAL_CURRENT_VISIT_MISMATCH");
        return visit.observation().point();
    }
    private static Observation observation(JsonNode row,JsonNode raw,Crs crs) {
        if(raw.isNull()) {
            require(row.path("sourcePoint").isNull() && row.path("point").isNull(),"CANONICAL_NULL_SOURCE_MISMATCH");return new Observation(null,null);
        }
        var original=sourcePoint(raw);require(original.equals(sourcePoint(row.path("sourcePoint"))),"CANONICAL_SOURCE_POINT_MISMATCH");
        var p=row.path("point");XY xy=new XY(number(p.path("x")),number(p.path("y")));
        var independent=NativeReferenceProjection.fromWgs84(crs,original);
        require(Math.hypot(independent.x()-xy.x(),independent.y()-xy.y())<=.001,"CANONICAL_INDEPENDENT_PROJECTION_MISMATCH");
        return new Observation(original,xy);
    }
    private static RouteGeometry.Point sourcePoint(JsonNode value) {
        return new RouteGeometry.Point(number(value.path("x")),number(value.path("y")));
    }
    private static String hash(JsonNode node,String field) {
        String value=text(node,field);require(value.matches("[0-9a-f]{64}"),"CANONICAL_HASH_REQUIRED");return value;
    }
    static String canonicalSha(JsonNode node) {return RouteSourceReceipt.sha256(JSON.writeValueAsBytes(sorted(node)));}
    private static JsonNode sorted(JsonNode node) {
        if(node.isObject()) {
            var out=JSON.createObjectNode();node.properties().stream().sorted(Map.Entry.comparingByKey()).forEach(p->out.set(p.getKey(),sorted(p.getValue())));return out;
        }
        if(node.isArray()) {var out=JSON.createArrayNode();for(var value:node)out.add(sorted(value));return out;}
        return node;
    }
}
