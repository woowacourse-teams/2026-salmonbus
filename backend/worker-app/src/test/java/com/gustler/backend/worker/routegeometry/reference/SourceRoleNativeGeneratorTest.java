package com.gustler.backend.worker.routegeometry.reference;

import static org.assertj.core.api.Assertions.*;
import com.gustler.backend.routegeometry.api.*;
import com.gustler.backend.routegeometry.engine.api.*;
import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;

/** Complete bounded source-only search slice, with no selected path or evaluation answer. */
class SourceRoleNativeGeneratorTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String SHA="3cd14d82f95c0530e11af92527a324e993a4c189ad0d644938200c752f5f95ef";
    private static final String RAW="3898bf3e38e664c13ebbb81f3bf716e8bb2522a43ae54644ddc843b826b64aa9";
    private static final Set<String> INPUTS=Set.of(
        "df58a16f29fd09a9cdf2bb314582d51928aa1ec54389225cec792afc803c20dd",
        "4058867f71541ecdc85df658492ab912830b945535ffc0eb70b4c54d51b9f804",
        "ae45e7b13e117a96c168cffdaf39e1e05500d189076cf0bd4e9cb7f56cefa941",
        "efb7f059f5b568dde49fbf2632290cc89f8d97cd992fedf462e46a4851baf6da",RAW,
        "a5c88f08738421050881304d90a0fe96855ffda31308a5beb4bff455efcc3215",
        "9413c07071459ac567f90f6ea0bc7dbe8f418bae3d7306d3773e928a5360d0f7",
        "a40b416e9df151c155f3170eed434faea46d8ec67f5e81f6801992eec87c1869");
    private static byte[] bytes() {
        try(var in=new GZIPInputStream(SourceRoleNativeGeneratorTest.class.getResourceAsStream("/reference/native-search-v1.json.gz"))) {return in.readAllBytes();}
        catch(IOException e){throw new UncheckedIOException(e);}
    }
    static PinnedRouteSearchGraph load(){return PinnedRouteSearchGraph.load(bytes(),new PinnedRouteSearchGraph.Pin(SHA,RAW,INPUTS),JSON);}
    private static RouteGeometry.Point point(JsonNode p){return new RouteGeometry.Point(p.path("x").asDouble(),p.path("y").asDouble());}
    static RouteGeometry geometry(PinnedRouteSearchGraph graph){
        var raw=graph.rawSource();var stops=new ArrayList<RouteGeometry.Stop>();var points=new ArrayList<RouteGeometry.Point>();
        for(var s:raw.path("busStops"))stops.add(new RouteGeometry.Stop(s.path("stationId").asString(),s.path("stopSequence").asInt(),s.path("name").asString(),s.path("direction").asString(),s.path("boardingAllowed").asBoolean(),s.path("point").isNull()?null:point(s.path("point"))));
        for(var p:raw.path("points"))points.add(point(p));
        return new RouteGeometry(raw.path("routeId").asString(),56L,"7007-1","start","end",56,stops,new RouteGeometry.Shape(new UUID(0,1),Instant.EPOCH,points,RouteShapeEvidence.EMPTY));
    }
    static RouteModelFacts facts(RouteGeometry source,PinnedRouteSearchGraph graph){
        var rows=graph.sourcePayload("stations1").at("/response/msgBody/busRouteStationList");var visits=new ArrayList<RouteModelFacts.VisitFacts>();
        for(int i=0;i<source.busStops().size();i++){var stop=source.busStops().get(i);var row=rows.get(i);
            assertThat(row.path("stationId").asString()).isEqualTo(stop.stationId());
            visits.add(new RouteModelFacts.VisitFacts(stop.stationId(),stop.stopSequence(),stop.direction(),new RouteModelFacts.Fact(RouteModelFacts.FieldState.VALUE,row.path("centerYn").asString())));}
        return new RouteModelFacts(RouteDisplayGeneration.input(source).digest(),Set.of(RouteModelFacts.Role.CENTER_YN),Map.of(),visits,RouteModelFacts.Availability.RESOLVED,RouteModelFacts.Reason.AVAILABLE,List.of());
    }
    @Test void actual_java_search_generates_native_candidate_without_selected_path_input(){
        var graph=load();var source=geometry(graph);assertThat(graph.edges()).hasSize(1098);
        var result=new SourceRoleNativeGenerator(SourceRoleNativeGenerator.Config.frozenV3()).generate(source,facts(source,graph),graph,63,64);
        assertThat(result.status()).isEqualTo("CANDIDATE_ONLY");var fragment=result.candidate().orElseThrow();
        assertThat(fragment.sourceWindow().startPointIndex()).isEqualTo(831);assertThat(fragment.sourceWindow().endPointIndex()).isEqualTo(835);
        assertThat(fragment.visits()).extracting(SourceRoleNativeGenerator.Visit::sequence).containsExactly(63,64);
        assertThat(fragment.points()).hasSizeGreaterThan(2);assertThat(fragment.sourceOccurrenceTrace()).hasSize(5);
        assertThat(result.published()).isFalse();assertThat(fragment.wholeRouteCoverageVerified()).isFalse();
        assertThat(fragment.accuracyGrade()).isEqualTo("UNEVALUATED");assertThat(result.sourceFallbackPoints()).isEqualTo(source.points());
        try{java.nio.file.Files.createDirectories(java.nio.file.Path.of("build/reports"));java.nio.file.Files.writeString(java.nio.file.Path.of("build/reports/java-native-search-replay.json"),JSON.writeValueAsString(result));}catch(IOException e){throw new UncheckedIOException(e);}
    }
    @Test void independent_server_pin_rejects_bundle_or_model_input_changes(){
        assertThatThrownBy(()->PinnedRouteSearchGraph.load(bytes(),new PinnedRouteSearchGraph.Pin("0".repeat(64),RAW,INPUTS),JSON)).hasMessage("SEARCH_HASH_MISMATCH");
        assertThatThrownBy(()->PinnedRouteSearchGraph.load(bytes(),new PinnedRouteSearchGraph.Pin(SHA,RAW,Set.of(RAW)),JSON)).hasMessage("MODEL_INPUT_PIN_MISMATCH");
    }
    @Test void scope_change_cannot_reuse_smaller_search_envelope(){
        var graph=load();var source=geometry(graph);
        assertThatThrownBy(()->new SourceRoleNativeGenerator(SourceRoleNativeGenerator.Config.frozenV3()).generate(source,facts(source,graph),graph,64,65)).isInstanceOf(IllegalArgumentException.class).hasMessage("SEARCH_SCOPE_MISMATCH");
    }
    @Test void unresolved_roles_preserve_source_without_generating_candidate(){
        var graph=load();var source=geometry(graph);var unresolved=RouteModelFacts.unresolved(RouteDisplayGeneration.input(source),Set.of(RouteModelFacts.Role.CENTER_YN),RouteModelFacts.Reason.SOURCE_UNAVAILABLE);
        var result=new SourceRoleNativeGenerator(SourceRoleNativeGenerator.Config.frozenV3()).generate(source,unresolved,graph,63,64);
        assertThat(result.status()).isEqualTo("SOURCE_FALLBACK");assertThat(result.candidate()).isEmpty();assertThat(result.sourceFallbackPoints()).isEqualTo(source.points());
    }
    @Test void repinned_duplicate_global_edge_cannot_claim_complete_search(){
        var tree=JSON.readTree(bytes());((ObjectNode)tree.path("engineEdges").get(0)).put("globalIndex",805);byte[] changed=JSON.writeValueAsBytes(tree);
        assertThatThrownBy(()->PinnedRouteSearchGraph.load(changed,new PinnedRouteSearchGraph.Pin(RouteSourceReceipt.sha256(changed),RAW,INPUTS),JSON)).hasMessage("INVALID_GLOBAL_EDGE_ORDER");
    }
    @Test void repinned_crs_definition_change_cannot_reinterpret_engine_coordinates() {
        var tree=JSON.readTree(bytes());var envelope=(ObjectNode)tree.path("searchEnvelope");
        String original=envelope.path("engineCrsWkt").asString();
        assertThat(envelope.path("engineCrsAuthority").isNull()).isTrue();
        String changed=original.replace("200000,","1000000,");
        assertThat(changed).isNotEqualTo(original);
        envelope.put("engineCrsWkt",changed);
        byte[] packet=JSON.writeValueAsBytes(tree);
        assertThatThrownBy(()->PinnedRouteSearchGraph.load(packet,new PinnedRouteSearchGraph.Pin(RouteSourceReceipt.sha256(packet),RAW,INPUTS),JSON))
            .hasMessage("UNSUPPORTED_ENGINE_CRS_DEFINITION");
    }
    @Test void repinned_authority_must_match_the_recognized_wkt_definition() {
        var tree=JSON.readTree(bytes());((ObjectNode)tree.path("searchEnvelope")).put("engineCrsAuthority","EPSG:5179");
        byte[] packet=JSON.writeValueAsBytes(tree);
        assertThatThrownBy(()->PinnedRouteSearchGraph.load(packet,new PinnedRouteSearchGraph.Pin(RouteSourceReceipt.sha256(packet),RAW,INPUTS),JSON))
            .hasMessage("ENGINE_CRS_AUTHORITY_MISMATCH");
    }

    @Test void immutable_engine_attrs_preserve_original_json_numeric_types_instead_of_string_coercion() {
        var graph=load();var rows=graph.tree().path("engineEdges");boolean sawNativeNumeric=false;
        for(int i=0;i<rows.size();i++) {
            var declared=rows.get(i).path("attrs");var actual=graph.edges().get(i).attrs();
            JsonNode serializedAttributes=JSON.valueToTree(actual);assertThat(serializedAttributes).isEqualTo(declared);
            for(var property:declared.properties())if(property.getValue().isNumber()) {
                sawNativeNumeric=true;assertThat(actual.get(property.getKey())).isInstanceOf(Number.class);
                assertThat(actual.get(property.getKey())).isNotInstanceOf(String.class);
            }
        }
        assertThat(sawNativeNumeric).isTrue();
        assertThatThrownBy(()->graph.edges().getFirst().attrs().put("ROAD_USE","1")).isInstanceOf(UnsupportedOperationException.class);
    }

}
