package com.gustler.backend.worker.routegeometry.reference;

import static com.gustler.backend.worker.routegeometry.reference.ReferenceJson.*;

import com.gustler.backend.routegeometry.engine.api.NativeReferenceProjection;
import com.gustler.backend.routegeometry.engine.api.NativeReferenceProjection.Crs;
import com.gustler.backend.routegeometry.api.RouteSourceReceipt;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import com.gustler.backend.worker.routegeometry.reference.PinnedNativeCatalog.NativeXYZ;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Immutable, server-pinned complete search input relative to the registered source graph, never a chosen answer path. */
public final class PinnedRouteSearchGraph {
    // Supported registered ITS frame, in canonical pyproj WKT2 serialization: GRS80 TM,
    // longitude 127, latitude 38, scale 1, false easting/northing 200000/600000,
    // metre EASTING_NORTHING. This definition is equivalent to EPSG:5186. Reject
    // other definitions/serializations until independently reviewed; a bundle pin
    // establishes provenance but cannot change the projection used by this engine.
    private static final String ENGINE_WKT_SHA256="3b973a7548af9989287e13c15ffed0d56a622f79cbea2d98023a3840432a5438";
    public record Pin(String bytesSha256,String rawSourceSha256,Set<String> modelInputSha256) {
        public Pin {
            if(!hash(bytesSha256)||!hash(rawSourceSha256)) throw new IllegalArgumentException("INVALID_SEARCH_PIN");
            modelInputSha256=Set.copyOf(modelInputSha256);
            if(modelInputSha256.isEmpty() || !modelInputSha256.stream().allMatch(PinnedRouteSearchGraph::hash))
                throw new IllegalArgumentException("INVALID_MODEL_INPUT_PIN");
        }
    }
    public record XY(double x,double y) {
        public XY { if(!Double.isFinite(x)||!Double.isFinite(y)) throw new IllegalArgumentException("INVALID_ENGINE_POINT"); }
    }
    public record NativeFeature(String id,String namespace,String fromNode,String toNode,Crs crs,
            List<NativeXYZ> xyz,String sourceLayer,String modelVersion,Map<String,Object> sourceAttrs,boolean topologyValid) {
        public NativeFeature { xyz=List.copyOf(xyz);sourceAttrs=immutable(sourceAttrs); }
    }
    public record Edge(int index,String id,String start,String end,List<XY> pointsXY,
            Map<String,Object> attrs,String source,NativeFeature nativeFeature) {
        public Edge { pointsXY=List.copyOf(pointsXY);attrs=immutable(attrs); }
    }
    private final Pin pin;
    private final CanonicalSourceObservations canonical;
    private final List<Edge> edges;
    private final Map<Integer,Edge> indices;
    private final Map<String,List<Edge>> outgoing;
    private final JsonNode root;
    private final JsonNode raw;
    private final JsonNode web;
    private final Map<String,JsonNode> payloads;
    private PinnedRouteSearchGraph(Pin pin,List<Edge> edges,JsonNode root,Map<String,JsonNode> payloads,CanonicalSourceObservations canonical) {
        this.canonical=canonical;this.pin=pin;this.edges=List.copyOf(edges);this.root=root.deepCopy();
        Map<String,JsonNode> copies=new LinkedHashMap<>();payloads.forEach((k,v)->copies.put(k,v.deepCopy()));
        this.payloads=Collections.unmodifiableMap(copies);this.raw=copies.get("raw");this.web=copies.get("web");
        Map<Integer,Edge> byIndex=new LinkedHashMap<>();Map<String,List<Edge>> byNode=new LinkedHashMap<>();
        for(Edge edge:edges) {byIndex.put(edge.index(),edge);byNode.computeIfAbsent(edge.start(),k->new java.util.ArrayList<>()).add(edge);}
        this.indices=Map.copyOf(byIndex);Map<String,List<Edge>> frozen=new LinkedHashMap<>();
        byNode.forEach((k,v)->frozen.put(k,List.copyOf(v)));this.outgoing=Map.copyOf(frozen);
    }
    public static PinnedRouteSearchGraph load(byte[] bytes,Pin pin,ObjectMapper mapper) {
        java.util.Objects.requireNonNull(pin);java.util.Objects.requireNonNull(mapper);
        require(bytes!=null && bytes.length<=16_000_000,"JSON_TOO_LARGE");
        require(RouteSourceReceipt.sha256(bytes).equals(pin.bytesSha256()),"SEARCH_HASH_MISMATCH");
        JsonNode root=read(bytes,16_000_000,1_000_000);
        String format=text(root,"format");
        require(Set.of("route-native-search-input-v1","route-native-search-input-v2").contains(format),"UNSUPPORTED_SEARCH_FORMAT");
        requireSupportedEngineFrame(root.path("searchEnvelope"));
        require(root.path("selectedEdgesRead").isBoolean() && !root.path("selectedEdgesRead").booleanValue()
            && root.path("groundTruthRead").isBoolean() && !root.path("groundTruthRead").booleanValue()
            && !root.path("wholeRouteGradeApproved").asBoolean(true),"SEARCH_INPUT_MUST_BE_SOURCE_ONLY");
        Map<String,JsonNode> payloads=new LinkedHashMap<>();Set<String> inputs=new HashSet<>();
        for(String role:List.of("raw","web","stations1","stations2")) {
            var packet=root.path("sourcePayloads").path(role);String body=text(packet,"utf8"),sha=text(packet,"sha256");
            byte[] raw=body.getBytes(StandardCharsets.UTF_8);
            require(raw.length<=1_000_000 && RouteSourceReceipt.sha256(raw).equals(sha),"SOURCE_PAYLOAD_HASH_MISMATCH");
            if(role.equals("raw"))require(sha.equals(pin.rawSourceSha256()),"RAW_SOURCE_PIN_MISMATCH");
            inputs.add(sha);payloads.put(role,read(raw,1_000_000));
        }
        require(payloads.get("stations1").path("response").path("msgBody").equals(payloads.get("stations2").path("response").path("msgBody")),"OFFICIAL_STATION_SNAPSHOTS_DISAGREE");
        require(root.path("referenceSources").isArray(),"REFERENCE_SOURCES_REQUIRED");
        Set<String> references=new HashSet<>();
        for(var row:root.path("referenceSources")) {String sha=text(row,"sha256");require(hash(sha),"INVALID_REFERENCE_HASH");inputs.add(sha);references.add(sha);}
        var canonical=format.equals("route-native-search-input-v2")?CanonicalSourceObservations.load(root,payloads.get("raw"),pin.rawSourceSha256()):null;
        if(canonical!=null)inputs.addAll(canonical.inputDigests());
        inputs.addAll(TurnWitnessEvidence.inputDigests(root));
        require(inputs.equals(pin.modelInputSha256()),"MODEL_INPUT_PIN_MISMATCH");
        Map<String,JsonNode> natives=new LinkedHashMap<>();
        for(var row:root.path("nativeCatalog").path("links"))require(natives.put(text(row,"id"),row)==null,"DUPLICATE_NATIVE_FEATURE");
        var rows=root.path("engineEdges");require(rows.isArray() && !rows.isEmpty() && rows.size()<=20_000,"INVALID_ENGINE_EDGES");
        List<Edge> edges=new ArrayList<>();Set<String> ids=new HashSet<>();int previous=-1,total=0;
        for(var row:rows) {
            int index=integer(row.path("globalIndex"));String id=text(row,"id"),from=text(row,"start"),to=text(row,"end"),source=text(row,"source");
            require(index>previous && index<integer(root.path("globalGraph").path("edgeCount")) && ids.add(id),"INVALID_GLOBAL_EDGE_ORDER");previous=index;
            require(references.contains(text(row.path("origin"),"sourceSha256")),"EDGE_SOURCE_NOT_PINNED");
            List<XY> xy=new ArrayList<>();require(row.path("pointsXY").isArray() && row.path("pointsXY").size()>=2,"INVALID_ENGINE_COORDINATES");
            for(var point:row.path("pointsXY")) {require(point.isArray() && point.size()==2,"ENGINE_XY_REQUIRED");xy.add(new XY(number(point.get(0)),number(point.get(1))));}
            total+=xy.size();require(total<=500_000,"ENGINE_VERTEX_BUDGET_EXCEEDED");
            Map<String,Object> attrs=scalarMap(row.path("attrs"));NativeFeature feature=null;
            if(source.equals("NGII")) {
                var n=natives.get(id);require(n!=null && n.path("attributes").equals(row.path("attrs")),"NATIVE_ENGINE_RECORD_MISMATCH");
                String ns=text(n,"namespace");String nativeFrom=text(n,"fromNodeId"),nativeTo=text(n,"toNodeId");
                require(id.equals(ns+":links:"+text(n,"sourceId")),"NATIVE_IDENTITY_MISMATCH");
                Crs crs=PinnedNativeCatalog.crs(text(n,"horizontalCrs"));var xyz=PinnedNativeCatalog.xyz(n.path("xyz"));
                List<XY> cleaned=new ArrayList<>();
                for(var vertex:xyz) {
                    var projected=NativeReferenceProjection.fromWgs84(Crs.EPSG_5186,NativeReferenceProjection.toWgs84(crs,vertex.xy()));
                    XY q=new XY(projected.x(),projected.y());
                    if(cleaned.isEmpty() || Math.hypot(cleaned.getLast().x()-q.x(),cleaned.getLast().y()-q.y())>.001)cleaned.add(q);
                }
                require(cleaned.size()==xy.size(),"NATIVE_ENGINE_VERTEX_MISMATCH");
                for(int i=0;i<xy.size();i++)require(Math.hypot(cleaned.get(i).x()-xy.get(i).x(),cleaned.get(i).y()-xy.get(i).y())<=.001,"NATIVE_ENGINE_FRAME_MISMATCH");
                boolean topology=n.path("explicitTopologyComplete").asBoolean(false) && n.path("continuousSinglePart").asBoolean(false)
                    && from.equals(nativeFrom) && to.equals(nativeTo);
                feature=new NativeFeature(id,ns,nativeFrom,nativeTo,crs,xyz,text(n,"sourceLayer"),text(n,"modelVersion"),attrs,topology);
            } else require(source.equals("ITS") && id.equals("ITS:"+attrs.get("LINK_ID")),"UNSUPPORTED_ENGINE_SOURCE");
            edges.add(new Edge(index,id,from,to,xy,attrs,source,feature));
        }
        var envelope=root.path("searchEnvelope");require("EASTING_NORTHING_ALWAYS_XY".equals(text(envelope,"axisOrder"))
            && "COMPLETE_RELATIVE_TO_PINNED_REGISTERED_GRAPH".equals(text(envelope,"claim"))
            && integer(envelope.path("envelopeEdgeCount"))==edges.size(),"SEARCH_ENVELOPE_MISMATCH");
        var indices=envelope.path("envelopeGlobalIndices");require(indices.isArray() && indices.size()==edges.size(),"SEARCH_EDGE_COVERAGE_MISMATCH");
        for(int i=0;i<edges.size();i++)require(integer(indices.get(i))==edges.get(i).index(),"SEARCH_EDGE_COVERAGE_MISMATCH");
        return new PinnedRouteSearchGraph(pin,edges,root,payloads,canonical);
    }
    private static void requireSupportedEngineFrame(JsonNode envelope) {
        String wkt=text(envelope,"engineCrsWkt");
        require(ENGINE_WKT_SHA256.equals(RouteSourceReceipt.sha256(wkt.getBytes(StandardCharsets.UTF_8))),"UNSUPPORTED_ENGINE_CRS_DEFINITION");
        JsonNode authority=envelope.path("engineCrsAuthority");
        // The registered source has no authority assigned by pyproj. Resolve only its
        // exact known WKT above; any supplied authority must agree with that resolution.
        require(authority.isNull() || authority.isString() && "EPSG:5186".equals(authority.asString()),"ENGINE_CRS_AUTHORITY_MISMATCH");
        require("EASTING_NORTHING_ALWAYS_XY".equals(text(envelope,"axisOrder")),"UNSUPPORTED_ENGINE_AXIS_ORDER");
    }
    public String digest() { return pin.bytesSha256(); }
    public String rawSourceSha() { return pin.rawSourceSha256(); }
    public Crs engineCrs() { return Crs.EPSG_5186; }
    public List<Edge> edges() { return edges; }
    public List<Edge> out(String node) { return outgoing.getOrDefault(node,List.of()); }
    public Optional<Edge> edgeByIndex(int index) { return Optional.ofNullable(indices.get(index)); }
    public XY sourcePoint(Crs crs,int index,com.gustler.backend.routegeometry.engine.api.RouteGeometry.Point source) {
        if(canonical!=null)return canonical.sourcePoint(crs,index,source);
        var p=NativeReferenceProjection.fromWgs84(crs,source);return new XY(p.x(),p.y());
    }
    public XY sourceVisit(Crs crs,com.gustler.backend.routegeometry.engine.api.RouteGeometry.Stop stop) {
        if(canonical!=null)return canonical.sourceVisit(crs,stop);
        var p=NativeReferenceProjection.fromWgs84(crs,stop.point());return new XY(p.x(),p.y());
    }
    public boolean usesCanonicalObservations(){return canonical!=null;}
    public JsonNode tree() { return root.deepCopy(); }
    public void assertSearchCovered(int fromSequence,int toSequence,int sourceStart,int sourceEnd,
            double requiredPathLimit,List<XY> stationXY) {
        var scope=root.path("scope");var window=scope.path("sourceWindow");var env=root.path("searchEnvelope");
        require(fromSequence==integer(scope.path("fromSequence")) && toSequence==integer(scope.path("toSequence"))
            && sourceStart==integer(window.path("startPointIndex")) && sourceEnd==integer(window.path("endPointIndex")),"SEARCH_SCOPE_MISMATCH");
        require(Double.isFinite(requiredPathLimit) && requiredPathLimit>0 && stationXY.size()==2,"INVALID_SEARCH_REQUEST");
        double limit=number(env.path("pathLimitMeters")),radius=number(env.path("endpointRadiusMeters"));
        require(Math.abs(limit-requiredPathLimit)<=.001 && radius==45,"SEARCH_LIMIT_MISMATCH");
        var boxes=env.path("boxes");require(boxes.isArray() && boxes.size()==2,"SEARCH_BOXES_REQUIRED");
        for(int i=0;i<2;i++) {
            var box=boxes.get(i);require(box.isArray() && box.size()==4,"INVALID_SEARCH_BOX");
            var station=stationXY.get(i);double r=radius+(i==0?requiredPathLimit:0);
            require(number(box.get(0))<=station.x()-r && number(box.get(1))<=station.y()-r
                && number(box.get(2))>=station.x()+r && number(box.get(3))>=station.y()+r,"INCOMPLETE_SEARCH_ENVELOPE");
        }
    }
    public JsonNode providerWeb() { return web.deepCopy(); }
    public JsonNode rawSource() { return raw.deepCopy(); }
    public JsonNode sourcePayload(String role) {
        JsonNode value=payloads.get(role);if(value==null)throw new IllegalArgumentException("UNKNOWN_SOURCE_PAYLOAD");return value.deepCopy();
    }
    private static Map<String,Object> scalarMap(JsonNode node) {
        require(node.isObject(),"ATTRIBUTES_REQUIRED");Map<String,Object> out=new LinkedHashMap<>();
        for(var property:node.properties()) {
            var value=property.getValue();require(value.isString() || value.isNumber() || value.isNull(),"SCALAR_ATTRIBUTE_REQUIRED");
            Object scalar;
            if(value.isNull())scalar=null;
            else if(value.isString())scalar=value.asString();
            else if(value.isIntegralNumber()) {
                if(value.canConvertToInt())scalar=value.intValue();
                else if(value.canConvertToLong())scalar=value.longValue();
                else scalar=value.bigIntegerValue();
            }
            else {
                require(Double.isFinite(value.doubleValue()),"FINITE_ATTRIBUTE_NUMBER_REQUIRED");
                if(value.isBigDecimal())scalar=value.decimalValue();else scalar=value.doubleValue();
            }
            out.put(property.getKey(),scalar);
        }
        return out;
    }
    private static Map<String,Object> immutable(Map<String,Object> value) { return Collections.unmodifiableMap(new LinkedHashMap<>(value)); }
    private static boolean hash(String value) { return value!=null && value.matches("[0-9a-f]{64}"); }
}
