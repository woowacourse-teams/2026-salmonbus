package com.gustler.backend.routecatalog.infrastructure.jdbc;

import com.gustler.backend.routecatalog.api.ReadRouteModelFacts;
import com.gustler.backend.routecatalog.domain.geometry.RouteSourceArchive;
import com.gustler.backend.routegeometry.api.RouteDisplayInput;
import com.gustler.backend.routegeometry.api.RouteModelFacts;
import com.gustler.backend.routegeometry.api.RouteModelFacts.*;
import com.gustler.backend.routegeometry.api.RouteSourceReceipt;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Reads only the sealed primary catalog responses; no generic JSON selector, caller facts, or external fetch. */
@Repository
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class JdbcRouteModelFactsReader implements ReadRouteModelFacts {
    private final JdbcClient jdbc;
    private final RouteSourceArchive archive;
    private final ObjectMapper json;
    public JdbcRouteModelFactsReader(JdbcClient jdbc,RouteSourceArchive archive,ObjectMapper json) {
        this.jdbc=jdbc;this.archive=archive;this.json=json;
    }
    private record Current(long id,Integer turn,String manifestId) {}
    @Override public RouteModelFacts read(Set<Role> consumed,RouteDisplayInput input,long versionId) {
        consumed=Set.copyOf(consumed);
        if(consumed.isEmpty()) return RouteModelFacts.notRequired(input);
        var current=jdbc.sql("""
            SELECT v.id,v.turn_sequence,v.acquisition_manifest_id FROM route r
            JOIN route_version v ON v.route_id=r.id AND v.valid_to IS NULL
            WHERE r.source_id='GBIS' AND r.public_route_id=?
            """).param(input.routeId()).query((r,n)->new Current(r.getLong(1),r.getObject(2,Integer.class),r.getString(3))).optional();
        if(current.isEmpty() || current.get().id()!=versionId)
            return unresolved(input,consumed,Reason.CURRENT_VERSION_MISMATCH);
        try {
            var visits=jdbc.sql("""
                SELECT s.stop_id,s.stop_order,s.direction,s.boarding_allowed,l.x,l.y
                FROM route_stop s LEFT JOIN LATERAL (
                  SELECT x,y FROM route_stop_location_history h
                  WHERE h.route_version_id=s.route_version_id AND h.stop_order=s.stop_order
                  ORDER BY h.observed_at DESC LIMIT 1
                ) l ON true WHERE s.route_version_id=? ORDER BY s.stop_order
                """).param(versionId).query((r,n)->new RouteDisplayInput.Visit(r.getString(1),r.getInt(2),r.getString(3),
                    r.getBoolean(4),point(r.getObject(5,Double.class),r.getObject(6,Double.class)))).list();
            var points=jdbc.sql("SELECT points::text FROM route_geometry WHERE route_version_id=? ORDER BY received_at DESC,id DESC LIMIT 1")
                .param(versionId).query(String.class).optional();
            if(points.isEmpty()) return unresolved(input,consumed,Reason.CURRENT_INPUT_MISMATCH);
            var actual=new RouteDisplayInput(input.routeId(),current.get().turn(),
                json.readValue(points.get(),new TypeReference<List<RouteDisplayInput.Point>>() {}),visits);
            if(!actual.digest().equals(input.digest())) return unresolved(input,consumed,Reason.CURRENT_INPUT_MISMATCH);
        } catch(IllegalArgumentException|tools.jackson.core.JacksonException invalid) {
            return unresolved(input,consumed,Reason.CURRENT_INPUT_MISMATCH);
        }
        String manifestId=current.get().manifestId();
        if(manifestId==null) return unresolved(input,consumed,Reason.CATALOG_MANIFEST_MISSING);
        final RouteSourceArchive.ArchivedManifest manifest;
        try { manifest=archive.restore(manifestId); }
        catch(RuntimeException unavailable) { return unresolved(input,consumed,Reason.SOURCE_UNAVAILABLE); }
        if(!manifest.purpose().equals("ROUTE_CATALOG_ACQUISITION") || !manifest.reusedManifestIds().isEmpty()
                || manifest.receipts().size()!=2)
            return unresolved(input,consumed,Reason.SOURCE_SCOPE_MISMATCH);
        var receipts=new HashMap<String,RouteSourceReceipt>();
        for(var receipt:manifest.receipts()) {
            if(!receipt.sourceId().equals("GBIS_API") || !receipt.scope().equals(input.routeId())
                    || !Set.of("ROUTE_INFO","ROUTE_STATIONS").contains(receipt.operation())
                    || receipts.put(receipt.operation(),receipt)!=null)
                return unresolved(input,consumed,Reason.SOURCE_SCOPE_MISMATCH);
        }
        final List<BasisRef> basis;
        try { basis=basis(manifest); }
        catch(RuntimeException invalid) { return unresolved(input,consumed,Reason.SOURCE_SCHEMA_INVALID); }
        if(receipts.values().stream().anyMatch(r->r.status()!=RouteSourceReceipt.Status.SUCCESS || r.body()==null))
            return RouteModelFacts.unresolved(input,consumed,Reason.SOURCE_UNAVAILABLE,basis);
        try {
            var info=response(receipts.get("ROUTE_INFO")).path("busRouteInfoItem");
            var stationNode=response(receipts.get("ROUTE_STATIONS")).path("busRouteStationList");
            if(!info.isObject()) throw new InvalidSource(Reason.SOURCE_SCHEMA_INVALID);
            assertRoute(info,input.routeId());
            var rows=new TreeMap<Integer,JsonNode>();
            List<JsonNode> stationRows=new ArrayList<>();
            if(stationNode.isArray()) stationNode.forEach(stationRows::add);
            else if(stationNode.isObject()) stationRows.add(stationNode);
            else throw new InvalidSource(Reason.SOURCE_SCHEMA_INVALID);
            for(var row:stationRows) {
                assertRoute(row,input.routeId());
                int sequence=integer(row.path("stationSeq"));
                if(sequence<1 || rows.put(sequence,row)!=null) throw new InvalidSource(Reason.SOURCE_SCHEMA_INVALID);
            }
            if(rows.size()!=input.visits().size() || !Objects.equals(turn(rows),input.turnSequence()))
                throw new InvalidSource(Reason.SOURCE_INPUT_MISMATCH);
            List<VisitFacts> visitFacts=new ArrayList<>();
            for(var visit:input.visits()) {
                var row=rows.get(visit.sequence());
                if(row==null || !visit.stationId().equals(row.path("stationId").asString(null))
                        || !Objects.equals(rawPoint(row),visit.sourcePoint())
                        || !visit.direction().equals(input.turnSequence()==null || visit.sequence()<=input.turnSequence()?"UP":"DOWN"))
                    throw new InvalidSource(Reason.SOURCE_INPUT_MISMATCH);
                if(consumed.contains(Role.CENTER_YN)) visitFacts.add(new VisitFacts(visit.stationId(),visit.sequence(),
                    visit.direction(),field(row,"centerYn",true)));
            }
            Map<Role,Fact> routeFacts=new EnumMap<>(Role.class);
            if(consumed.contains(Role.ROUTE_TYPE_CD)) routeFacts.put(Role.ROUTE_TYPE_CD,field(info,"routeTypeCd",false));
            if(consumed.contains(Role.BUS_TYPE_CD)) routeFacts.put(Role.BUS_TYPE_CD,field(info,"busTypeCd",false));
            List<Fact> values=new ArrayList<>(routeFacts.values());visitFacts.forEach(v->values.add(v.centerYn()));
            Reason reason=values.stream().anyMatch(f->f.state()==FieldState.MISSING)?Reason.CONSUMED_FIELD_MISSING
                :values.stream().anyMatch(f->f.state()==FieldState.NULL)?Reason.CONSUMED_FIELD_NULL
                :values.stream().anyMatch(f->f.state()==FieldState.UNKNOWN)?Reason.CONSUMED_FIELD_UNKNOWN:Reason.AVAILABLE;
            return new RouteModelFacts(input.digest(),consumed,routeFacts,visitFacts,
                reason==Reason.AVAILABLE?Availability.RESOLVED:Availability.UNRESOLVED,reason,basis);
        } catch(InvalidSource invalid) { return RouteModelFacts.unresolved(input,consumed,invalid.reason,basis); }
        catch(RuntimeException invalid) { return RouteModelFacts.unresolved(input,consumed,Reason.SOURCE_SCHEMA_INVALID,basis); }
    }
    private List<BasisRef> basis(RouteSourceArchive.ArchivedManifest manifest) {
        var receiptIds=json.readTree(manifest.document()).path("receiptIds");
        if(receiptIds.size()!=manifest.receipts().size()) throw new InvalidSource(Reason.SOURCE_SCHEMA_INVALID);
        List<BasisRef> basis=new ArrayList<>();
        for(int i=0;i<manifest.receipts().size();i++) {
            var receipt=manifest.receipts().get(i);
            basis.add(new BasisRef(manifest.manifestId(),receiptIds.get(i).asString(),receipt.operation(),receipt.bodySha256(),receipt.acquiredAt()));
        }
        return List.copyOf(basis);
    }
    private JsonNode response(RouteSourceReceipt receipt) {
        var root=json.readTree(receipt.body()).path("response");
        if(!"0".equals(root.path("msgHeader").path("resultCode").asString(null)) || !root.path("msgBody").isObject())
            throw new InvalidSource(Reason.SOURCE_SCHEMA_INVALID);
        return root.path("msgBody");
    }
    private static void assertRoute(JsonNode row,String routeId) {
        if(row.has("routeId") && !routeId.equals(row.path("routeId").asString(null)))
            throw new InvalidSource(Reason.SOURCE_SCOPE_MISMATCH);
    }
    private static Fact field(JsonNode row,String name,boolean center) {
        if(!row.has(name)) return new Fact(FieldState.MISSING,null);
        var value=row.path(name);
        if(value.isNull()) return new Fact(FieldState.NULL,null);
        if(!(value.isString() || !center && value.isIntegralNumber())) return Fact.unknown();
        String text=value.asString().trim();
        boolean known=center?Set.of("Y","N").contains(text):text.matches("[0-9]{1,12}");
        return known?new Fact(FieldState.VALUE,text):Fact.unknown();
    }
    private static Integer turn(SortedMap<Integer,JsonNode> rows) {
        Set<Integer> turns=new HashSet<>();List<Integer> marked=new ArrayList<>();
        for(var entry:rows.entrySet()) {
            var declared=entry.getValue().path("turnSeq");
            turns.add(declared.isMissingNode()||declared.isNull()?null:integer(declared));
            if("Y".equals(entry.getValue().path("turnYn").asString(null))) marked.add(entry.getKey());
        }
        if(turns.size()!=1 || turns.contains(null) || marked.size()!=1 || !marked.getFirst().equals(turns.iterator().next())) return null;
        return turns.iterator().next();
    }
    private static int integer(JsonNode node) {
        try { return Integer.parseInt(node.asString(null)); }
        catch(RuntimeException invalid) { throw new InvalidSource(Reason.SOURCE_SCHEMA_INVALID); }
    }
    private static RouteDisplayInput.Point rawPoint(JsonNode row) {
        try {
            Double x=row.path("x").isMissingNode()||row.path("x").isNull()?null:Double.valueOf(row.path("x").asString());
            Double y=row.path("y").isMissingNode()||row.path("y").isNull()?null:Double.valueOf(row.path("y").asString());
            return point(x,y);
        } catch(NumberFormatException invalid) { throw new InvalidSource(Reason.SOURCE_SCHEMA_INVALID); }
    }
    private static RouteDisplayInput.Point point(Double x,Double y) {
        if(x==null || y==null) return null;
        try { return new RouteDisplayInput.Point(x,y); }
        catch(IllegalArgumentException invalid) { return null; }
    }
    private static RouteModelFacts unresolved(RouteDisplayInput input,Set<Role> consumed,Reason reason) {
        return RouteModelFacts.unresolved(input,consumed,reason);
    }
    private static class InvalidSource extends RuntimeException {
        private final Reason reason;
        InvalidSource(Reason reason) { super(reason.name());this.reason=reason; }
    }
}
