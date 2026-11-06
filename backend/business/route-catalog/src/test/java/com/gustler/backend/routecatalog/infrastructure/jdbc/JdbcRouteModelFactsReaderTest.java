package com.gustler.backend.routecatalog.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.*;
import com.gustler.backend.routecatalog.api.ReadRouteModelFacts;
import com.gustler.backend.routecatalog.domain.geometry.RouteSourceArchive;
import com.gustler.backend.routegeometry.api.*;
import com.gustler.backend.routegeometry.api.RouteModelFacts.*;
import com.gustler.backend.support.PostgresTestContainer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes=JdbcRouteModelFactsReaderTest.Config.class)
class JdbcRouteModelFactsReaderTest {
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration
    @Import({PostgresTestContainer.class,JdbcRouteSourceArchive.class,JdbcRouteModelFactsReader.class})
    static class Config { @Bean ObjectMapper json() { return new ObjectMapper(); } }
    @Autowired JdbcClient jdbc;
    @Autowired RouteSourceArchive archive;
    @Autowired ReadRouteModelFacts reader;
    @Autowired ObjectMapper json;
    private static final String ROUTE="204000057";
    private static final Instant AT=Instant.parse("2026-11-02T00:00:00Z");
    private static final Set<Role> ALL=Set.of(Role.CENTER_YN,Role.ROUTE_TYPE_CD,Role.BUS_TYPE_CD);
    private long version;
    private RouteDisplayInput input;
    private String info() { return """
        {"response":{"msgHeader":{"resultCode":0,"queryTime":"old"},"msgBody":{"busRouteInfoItem":
          {"routeId":"204000057","routeTypeCd":11,"busTypeCd":"2"}}}}
        """; }
    private String stations() { return """
        {"response":{"msgHeader":{"resultCode":0,"queryTime":"old"},"msgBody":{"busRouteStationList":[
          {"routeId":"204000057","stationId":"206000001","stationSeq":1,"turnSeq":2,"turnYn":"N","x":127,"y":37,"centerYn":"N"},
          {"routeId":"204000057","stationId":"206000002","stationSeq":2,"turnSeq":2,"turnYn":"Y","x":127.01,"y":37.01,"centerYn":"Y"},
          {"routeId":"204000057","stationId":"206000001","stationSeq":3,"turnSeq":2,"turnYn":"N","x":127,"y":37,"centerYn":"N"}]}}}
        """; }
    @BeforeEach void seed() {
        jdbc.sql("TRUNCATE route,route_source_blob,route_source_receipt,route_source_manifest CASCADE").update();
        long route=jdbc.sql("INSERT INTO route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name) VALUES (?,'GBIS',?,'3330','A','B') RETURNING id")
            .params(ROUTE,ROUTE).query(Long.class).single();
        version=jdbc.sql("INSERT INTO route_version(route_id,turn_sequence,content_digest,valid_from) VALUES (?,2,?,'2026-11-01T00:00:00Z') RETURNING id")
            .params(route,"a".repeat(64)).query(Long.class).single();
        var a=new RouteDisplayInput.Point(127,37);var b=new RouteDisplayInput.Point(127.01,37.01);
        input=new RouteDisplayInput(ROUTE,2,List.of(a,b,a),List.of(
            new RouteDisplayInput.Visit("206000001",1,"UP",true,a),new RouteDisplayInput.Visit("206000002",2,"UP",true,b),
            new RouteDisplayInput.Visit("206000001",3,"DOWN",true,a)));
        for(var visit:input.visits()) {
            jdbc.sql("INSERT INTO route_stop(route_version_id,stop_order,stop_id,name,direction,boarding_allowed) VALUES (?,?,?,'A',?,true)")
                .params(version,visit.sequence(),visit.stationId(),visit.direction()).update();
            jdbc.sql("INSERT INTO route_stop_location_history(route_version_id,stop_order,stop_id,observed_at,x,y) VALUES (?,?,?,'2026-11-01T00:00:00Z',?,?)")
                .params(version,visit.sequence(),visit.stationId(),visit.sourcePoint().x(),visit.sourcePoint().y()).update();
        }
        jdbc.sql("INSERT INTO route_geometry(id,route_version_id,received_at,content_digest,points) VALUES (?,?,'2026-11-01T00:00:00Z',?,CAST(? AS jsonb))")
            .params(UUID.randomUUID(),version,"b".repeat(64),json.writeValueAsString(input.points())).update();
    }
    private RouteSourceReceipt receipt(String operation,String body,Instant at) {
        return new RouteSourceReceipt("GBIS_API",operation,ROUTE,RouteSourceReceipt.Status.SUCCESS,200,at,
            "NOT_PROVIDED",RouteSourceReceipt.CoordinateSystem.WGS84_ASSUMED,body.getBytes(StandardCharsets.UTF_8));
    }
    private String install(String info,String stations,Instant at) {
        String manifest=archive.record("ROUTE_CATALOG_ACQUISITION",List.of(receipt("ROUTE_INFO",info,at),
            receipt("ROUTE_STATIONS",stations,at)),List.of());pointer(manifest);return manifest;
    }
    private void pointer(String manifest) { jdbc.sql("UPDATE route_version SET acquisition_manifest_id=? WHERE id=?").params(manifest,version).update(); }
    private RouteModelFacts read(Set<Role> roles) { return reader.read(roles,input,version); }
    @Test void reads_only_actual_primary_receipts_and_preserves_each_visit_occurrence() {
        String manifest=install(info(),stations(),AT);var facts=read(ALL);
        assertThat(facts.availability()).isEqualTo(Availability.RESOLVED);
        assertThat(facts.visits()).extracting(v->v.centerYn().value()).containsExactly("N","Y","N");
        assertThat(facts.visits()).extracting(VisitFacts::stationId).containsExactly("206000001","206000002","206000001");
        assertThat(facts.routeValues().get(Role.ROUTE_TYPE_CD).value()).isEqualTo("11");
        assertThat(facts.routeValues().get(Role.BUS_TYPE_CD).value()).isEqualTo("2");
        assertThat(facts.basisRefs()).hasSize(2).allSatisfy(b -> {
            assertThat(b.catalogManifestId()).isEqualTo(manifest);assertThat(b.receiptId()).matches("[0-9a-f]{64}");
        });
    }
    @Test void center_or_route_type_changes_change_facts_with_identical_geometry_input_digest() {
        install(info(),stations(),AT);var original=read(ALL);
        install(info(),stations().replaceFirst(java.util.regex.Pattern.quote("\"centerYn\":\"N\""),"\"centerYn\":\"Y\""),AT.plusSeconds(1));
        var centerChanged=read(ALL);
        assertThat(centerChanged.availability()).isEqualTo(Availability.RESOLVED);
        assertThat(centerChanged.inputDigest()).isEqualTo(original.inputDigest());
        assertThat(centerChanged.factsDigest()).isNotEqualTo(original.factsDigest());
        install(info().replace("\"routeTypeCd\":11","\"routeTypeCd\":12"),stations(),AT.plusSeconds(2));
        assertThat(read(ALL).factsDigest()).isNotEqualTo(original.factsDigest());
    }
    @Test void whitespace_query_time_and_receipt_identity_do_not_change_model_semantics() {
        install(info(),stations(),AT);var original=read(ALL);
        install(" \n"+info().replace("old","new")+"\n ","\n"+stations().replace("old","new"),AT.plusSeconds(100));
        var refreshed=read(ALL);assertThat(refreshed.basisRefs()).isNotEqualTo(original.basisRefs());
        assertThat(refreshed.factsDigest()).isEqualTo(original.factsDigest());
    }
    @Test void missing_null_and_unknown_center_flags_are_distinct_unresolved_inputs() {
        Set<String> digests=new HashSet<>();
        for(var pair:Map.of(FieldState.MISSING,"",FieldState.NULL,",\"centerYn\":null",FieldState.UNKNOWN,",\"centerYn\":\"U\"").entrySet()) {
            String rows=stations().replaceFirst(java.util.regex.Pattern.quote(",\"centerYn\":\"N\""),pair.getValue());
            install(info(),rows,AT);var facts=read(Set.of(Role.CENTER_YN));
            assertThat(facts.availability()).isEqualTo(Availability.UNRESOLVED);
            assertThat(facts.visits().getFirst().centerYn().state()).isEqualTo(pair.getKey());
            assertThat(facts.visits().getFirst().centerYn().value()).isNull();digests.add(facts.factsDigest());
        }
        assertThat(digests).hasSize(3);
    }
    @Test void unconsumed_route_classification_fields_do_not_change_center_only_facts() {
        install(info(),stations(),AT);var before=read(Set.of(Role.CENTER_YN));
        install(info().replace("\"busTypeCd\":\"2\"","\"busTypeCd\":\"unknown\"").replace("\"routeTypeCd\":11","\"routeTypeCd\":null"),stations(),AT);
        var after=read(Set.of(Role.CENTER_YN));assertThat(after.availability()).isEqualTo(Availability.RESOLVED);
        assertThat(after.factsDigest()).isEqualTo(before.factsDigest());
        assertThat(read(Set.of(Role.ROUTE_TYPE_CD)).routeValues().get(Role.ROUTE_TYPE_CD).state()).isEqualTo(FieldState.NULL);
        assertThat(read(Set.of(Role.BUS_TYPE_CD)).routeValues().get(Role.BUS_TYPE_CD).state()).isEqualTo(FieldState.UNKNOWN);
    }
    @Test void missing_manifest_stale_input_or_wrong_version_is_not_resolved() {
        assertThat(read(ALL).reason()).isEqualTo(Reason.CATALOG_MANIFEST_MISSING);
        install(info(),stations(),AT);
        assertThat(reader.read(ALL,input,version+1).reason()).isEqualTo(Reason.CURRENT_VERSION_MISMATCH);
        var changed=new RouteDisplayInput(input.routeId(),input.turnSequence(),List.of(new RouteDisplayInput.Point(127.001,37),input.points().get(1),input.points().get(2)),input.visits());
        assertThat(reader.read(ALL,changed,version).reason()).isEqualTo(Reason.CURRENT_INPUT_MISMATCH);
    }
    @Test void withheld_or_wrong_route_receipts_are_never_model_facts() {
        var withheld=new RouteSourceReceipt("GBIS_API","ROUTE_INFO",ROUTE,RouteSourceReceipt.Status.SENSITIVE_RESPONSE,200,
            AT,"NOT_PROVIDED",RouteSourceReceipt.CoordinateSystem.UNKNOWN,null);
        pointer(archive.record("ROUTE_CATALOG_ACQUISITION",List.of(withheld,receipt("ROUTE_STATIONS",stations(),AT)),List.of()));
        var unavailable=read(ALL);
        assertThat(unavailable.reason()).isEqualTo(Reason.SOURCE_UNAVAILABLE);
        assertThat(unavailable.basisRefs()).hasSize(2);assertThat(unavailable.basisRefs().getFirst().bodySha256()).isNull();
        install(info().replace(ROUTE,"999999999"),stations(),AT);
        assertThat(read(ALL).reason()).isEqualTo(Reason.SOURCE_SCOPE_MISMATCH);
    }
    @Test void station_identity_turn_and_coordinates_must_match_both_source_and_current_state() {
        for(String rows:List.of(stations().replaceFirst("206000001","206000099"),
                stations().replace("\"turnSeq\":2","\"turnSeq\":3"),stations().replaceFirst("\"x\":127,","\"x\":127.0001,"))) {
            install(info(),rows,AT);assertThat(read(ALL).reason()).isEqualTo(Reason.SOURCE_INPUT_MISMATCH);
        }
        install(info(),stations(),AT);
        jdbc.sql("UPDATE route_stop_location_history SET x=127.0101 WHERE stop_order=2").update();
        assertThat(read(ALL).reason()).isEqualTo(Reason.CURRENT_INPUT_MISMATCH);
        var visits=new ArrayList<>(input.visits());var old=visits.get(1);
        visits.set(1,new RouteDisplayInput.Visit(old.stationId(),old.sequence(),old.direction(),old.boardingAllowed(),new RouteDisplayInput.Point(127.0101,37.01)));
        assertThat(reader.read(ALL,new RouteDisplayInput(ROUTE,2,input.points(),visits),version).reason()).isEqualTo(Reason.SOURCE_INPUT_MISMATCH);
    }
}
