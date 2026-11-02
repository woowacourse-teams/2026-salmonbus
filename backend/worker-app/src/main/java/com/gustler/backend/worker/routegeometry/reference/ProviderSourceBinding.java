package com.gustler.backend.worker.routegeometry.reference;

import static com.gustler.backend.worker.routegeometry.reference.ReferenceJson.require;

import com.gustler.backend.routegeometry.api.RouteModelFacts;
import com.gustler.backend.routegeometry.engine.api.NativeReferenceProjection;
import com.gustler.backend.routegeometry.engine.api.NativeReferenceProjection.Crs;
import com.gustler.backend.routegeometry.engine.api.NativeReferenceProjection.NativePoint;
import com.gustler.backend.routegeometry.engine.api.RouteDisplayGeneration;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;

/** Source-only binding from original provider order. No road candidate, evaluation answer, or chosen range is an input. */
public final class ProviderSourceBinding {
    private ProviderSourceBinding() {}
    public record Visit(String stationId,int sequence,String direction,int sourcePointIndex,String centerYn) {}
    public record Binding(String inputDigest,String factsDigest,List<Visit> visits,Map<Integer,Integer> pointRoles,double maximumCoordinateGapMeters) {
        public Binding { visits=List.copyOf(visits);pointRoles=Map.copyOf(pointRoles); }
    }
    public static Binding bind(RouteGeometry source,RouteModelFacts facts,JsonNode web) {
        String digest=RouteDisplayGeneration.input(source).digest();
        require(facts!=null && digest.equals(facts.inputDigest()),"MODEL_FACTS_INPUT_MISMATCH");
        require(facts.consumedRoles().equals(Set.of(RouteModelFacts.Role.CENTER_YN))
            && facts.availability()==RouteModelFacts.Availability.RESOLVED,"CENTER_FACTS_UNRESOLVED");
        require(facts.visits().size()==source.busStops().size(),"MODEL_FACT_VISITS_MISMATCH");
        var gg=web.path("result").path("gg");
        Map<Integer,JsonNode> rows=new TreeMap<>();Map<Integer,Set<String>> membership=new HashMap<>();
        for(String direction:List.of("UP","DOWN")) {
            var section=gg.path(direction.equals("UP")?"upLine":"downLine");var values=section.path("list");
            require(values.isArray() && scalarInt(section.path("count"))==values.size(),"PROVIDER_LINE_COUNT_MISMATCH");
            int previous=0,offset=0;
            for(var row:values) {
                int seq=scalarInt(row.path("seq")),type=scalarInt(row.path("type"));
                require(seq>previous && seq<=source.points().size() && type>=1 && type<=3,"PROVIDER_POINT_ORDER_OR_ROLE_INVALID");
                previous=seq;
                if(rows.containsKey(seq)) require(direction.equals("DOWN") && offset==0 && seq==rows.size()
                    && row.equals(rows.get(seq)),"CONFLICTING_PROVIDER_BOUNDARY");
                rows.put(seq,row);membership.computeIfAbsent(seq,x->new HashSet<>()).add(direction);offset++;
            }
        }
        require(rows.size()==source.points().size(),"PROVIDER_SOURCE_COVERAGE_MISMATCH");
        List<Integer> stationPoints=new ArrayList<>();Map<Integer,Integer> roles=new HashMap<>();double max=0;
        for(int sequence=1;sequence<=source.points().size();sequence++) {
            var row=rows.get(sequence);require(row!=null,"PROVIDER_SOURCE_SEQUENCE_GAP");
            double x=scalarDouble(row.path("lon")),y=scalarDouble(row.path("lat"));
            var actual=NativeReferenceProjection.toWgs84(Crs.EPSG_3857,new NativePoint(x,y));
            double gap=metres(actual,source.points().get(sequence-1));
            require(gap<=4,"PROVIDER_COORDINATES_DISAGREE_WITH_SOURCE");max=Math.max(max,gap);
            int role=scalarInt(row.path("type"));roles.put(sequence-1,role);if(role==1)stationPoints.add(sequence);
        }
        require(stationPoints.size()==source.busStops().size(),"PROVIDER_VISIT_COUNT_MISMATCH");
        List<Visit> visits=new ArrayList<>();
        for(int n=0;n<source.busStops().size();n++) {
            var stop=source.busStops().get(n);var fact=facts.visits().get(n);int pointSequence=stationPoints.get(n);
            require(stop.stationId().equals(fact.stationId()) && stop.stopSequence()==fact.sequence()
                && stop.direction().equals(fact.direction()) && membership.get(pointSequence).contains(stop.direction()),"PROVIDER_VISIT_IDENTITY_MISMATCH");
            require(fact.centerYn().state()==RouteModelFacts.FieldState.VALUE,"CENTER_FACT_UNRESOLVED");
            visits.add(new Visit(stop.stationId(),stop.stopSequence(),stop.direction(),pointSequence-1,fact.centerYn().value()));
        }
        return new Binding(digest,facts.factsDigest(),visits,roles,max);
    }
    private static int scalarInt(JsonNode n) {
        try {String text=n.asString();require(text!=null && text.matches("[0-9]{1,8}"),"INVALID_PROVIDER_INTEGER");return Integer.parseInt(text);}
        catch(RuntimeException invalid) {throw new IllegalArgumentException("INVALID_PROVIDER_INTEGER");}
    }
    private static double scalarDouble(JsonNode n) {
        try {double value=Double.parseDouble(n.asString());require(Double.isFinite(value),"INVALID_PROVIDER_COORDINATE");return value;}
        catch(RuntimeException invalid) {throw new IllegalArgumentException("INVALID_PROVIDER_COORDINATE");}
    }
    private static double metres(RouteGeometry.Point a,RouteGeometry.Point b) {
        double lat1=Math.toRadians(a.y()),lat2=Math.toRadians(b.y()),dy=lat2-lat1,dx=Math.toRadians(b.x()-a.x());
        double h=Math.pow(Math.sin(dy/2),2)+Math.cos(lat1)*Math.cos(lat2)*Math.pow(Math.sin(dx/2),2);
        return 6371008.8*2*Math.asin(Math.min(1,Math.sqrt(h)));
    }
}
