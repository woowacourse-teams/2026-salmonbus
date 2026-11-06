package com.gustler.backend.worker.routegeometry.reference;

import static org.assertj.core.api.Assertions.*;
import com.gustler.backend.routegeometry.api.RouteSourceReceipt;
import com.gustler.backend.routegeometry.engine.api.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;

class GlobalReferenceMatcherAdapterTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static byte[] resource(String name){
        try(var in=GlobalReferenceMatcherAdapterTest.class.getResourceAsStream("/reference/"+name)){
            if(name.endsWith(".gz"))try(var gzip=new GZIPInputStream(in)){return gzip.readAllBytes();}
            return in.readAllBytes();
        }catch(IOException failure){throw new UncheckedIOException(failure);}
    }
    private static ObjectNode packet(){
        var root=(ObjectNode)JSON.readTree(resource("native-search-v2.json.gz"));var evidence=JSON.readTree(resource("turn-witnesses-v1.json"));
        root.set("turnWitnessEvidence",evidence);((ObjectNode)root.path("producer")).put("turnWitnessExtractorSha256",evidence.at("/producer/sha256").asString());return root;
    }
    private static Set<String> inputs(JsonNode root){
        Set<String> hashes=new HashSet<>();for(var row:root.path("sourcePayloads").properties())hashes.add(row.getValue().path("sha256").asString());
        for(var row:root.path("referenceSources"))hashes.add(row.path("sha256").asString());var c=root.path("canonicalObservations");
        hashes.add(c.path("contentSha256").asString());hashes.add(c.at("/producer/sha256").asString());hashes.add(c.at("/environment/projDatabase/sha256").asString());
        for(var frame:c.path("frames"))hashes.add(frame.path("coordinatesSha256").asString());
        var e=root.path("turnWitnessEvidence");for(String field:List.of("contentSha256","sourceArchiveSha256"))hashes.add(e.path(field).asString());hashes.add(e.at("/producer/sha256").asString());return hashes;
    }
    private static PinnedRouteSearchGraph load(JsonNode root){
        byte[] bytes=JSON.writeValueAsBytes(root);
        return PinnedRouteSearchGraph.load(bytes,new PinnedRouteSearchGraph.Pin(RouteSourceReceipt.sha256(bytes),root.at("/sourcePayloads/raw/sha256").asString(),inputs(root)),JSON);
    }
    static PinnedRouteSearchGraph completeGraph(){return load(packet());}
    @Test void real_global_viterbi_is_source_scoped_and_preserves_precision_candidate_and_original_graph()throws IOException{
        var graph=completeGraph();var source=SourceRoleNativeGeneratorTest.geometry(graph);var facts=SourceRoleNativeGeneratorTest.facts(source,graph);
        var adapted=GlobalReferenceMatcherAdapter.adaptGraph(graph,List.of());assertThat(adapted.edges()).hasSize(graph.edges().size());
        var originalRows=graph.tree().path("engineEdges");
        for(int i=0;i<graph.edges().size();i++){
            var before=graph.edges().get(i);var after=adapted.edges().get(i);
            assertThat(after.originalIndex()).isEqualTo(before.index());assertThat(after.id()).isEqualTo(before.id());
            assertThat(after.from()).isEqualTo(before.start());assertThat(after.to()).isEqualTo(before.end());assertThat(after.attributes()).isEqualTo(before.attrs());
            JsonNode serializedAttributes=JSON.valueToTree(after.attributes());assertThat(serializedAttributes).isEqualTo(originalRows.get(i).path("attrs"));
        }
        var output=new GlobalReferenceMatcherAdapter().generate(source,facts,graph);
        assertThat(output.status()).isIn("MATCHED_DIAGNOSTIC","SOURCE_FALLBACK_DIAGNOSTIC");assertThat(output.globalResult()).isNotNull();
        assertThat(output.prepared().observations()).hasSizeGreaterThan(2);assertThat(output.sourceWindow().startPointIndex()).isEqualTo(831);
        assertThat(output.sourceWindow().endPointIndex()).isEqualTo(835);assertThat(output.globalResult().selections()).hasSize(output.prepared().observations().size());
        assertThat(output.prepared().observations()).allSatisfy(v->assertThat(v.allowedReferenceIds()).isNull());
        assertThat(output.bmsPriorStatus()).isEqualTo("UNKNOWN_NO_PINNED_BMS_SOURCE");assertThat(output.published()).isFalse();assertThat(output.wholeRouteCoverageVerified()).isFalse();
        assertThat(output.globalResult().geometryAccuracyVerified()).isFalse();assertThat(output.globalResult().routeLegalityVerified()).isFalse();
        var precision=new SourceRoleNativeGenerator(SourceRoleNativeGenerator.Config.frozenV3()).generate(source,facts,graph,63,64);
        assertThat(precision.candidate().orElseThrow().nativeCoordinatesXYZ()).hasSize(157);
        var path=Path.of("build/reports/global-java-native-shadow-diagnostic.json");Files.createDirectories(path.getParent());Files.writeString(path,JSON.writeValueAsString(output));
        var exact=Path.of("build/reports/global-java-precision-candidate.json");Files.writeString(exact,JSON.writeValueAsString(precision));
    }
    @Test void missing_original_witnesses_produce_insufficient_source_fallback_without_inference(){
        var base=(ObjectNode)JSON.readTree(resource("native-search-v2.json.gz"));byte[] bytes=JSON.writeValueAsBytes(base);
        var hashes=inputs(packet());var e=packet().path("turnWitnessEvidence");hashes.remove(e.path("contentSha256").asString());hashes.remove(e.path("sourceArchiveSha256").asString());hashes.remove(e.at("/producer/sha256").asString());
        var graph=PinnedRouteSearchGraph.load(bytes,new PinnedRouteSearchGraph.Pin(RouteSourceReceipt.sha256(bytes),base.at("/sourcePayloads/raw/sha256").asString(),hashes),JSON);
        var source=SourceRoleNativeGeneratorTest.geometry(graph);var output=new GlobalReferenceMatcherAdapter().generate(source,SourceRoleNativeGeneratorTest.facts(source,graph),graph);
        assertThat(output.status()).isEqualTo("INSUFFICIENT");assertThat(output.reason()).isEqualTo("ORIGINAL_TURN_WITNESSES_UNAVAILABLE");
        assertThat(output.globalResult()).isNull();assertThat(output.sourceFallbackPoints()).isEqualTo(source.points().subList(831,836));
    }
    @Test void even_re_pinned_turn_evidence_must_preserve_source_record_identity_and_full_scope(){
        List<Consumer<ObjectNode>> changes=List.of(
            r->((ArrayNode)r.at("/turnWitnessEvidence/sourceRows")).remove(0),
            r->((ObjectNode)r.at("/turnWitnessEvidence/sourceRows/0")).put("sourceRecordIndex",0),
            r->((ObjectNode)r.at("/turnWitnessEvidence/sourceRows/0/startLinks/0")).put("LINK_ID","fake-link"));
        for(var change:changes){var root=packet();change.accept(root);var e=(ObjectNode)root.path("turnWitnessEvidence");e.remove("contentSha256");e.put("contentSha256",CanonicalSourceObservations.canonicalSha(e));
            assertThatThrownBy(()->load(root)).isInstanceOf(IllegalArgumentException.class);}
    }
}
