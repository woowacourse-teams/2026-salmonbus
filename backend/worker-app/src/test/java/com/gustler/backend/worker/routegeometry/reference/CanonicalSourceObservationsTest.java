package com.gustler.backend.worker.routegeometry.reference;

import static org.assertj.core.api.Assertions.*;
import com.gustler.backend.routegeometry.api.RouteSourceReceipt;
import com.gustler.backend.routegeometry.engine.api.RouteDisplayGeneration;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;

/** Every source observation is checked before the source-only canonical frame can influence the search. */
class CanonicalSourceObservationsTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String RAW="3898bf3e38e664c13ebbb81f3bf716e8bb2522a43ae54644ddc843b826b64aa9";
    private static byte[] bytes(){
        try(var in=new GZIPInputStream(CanonicalSourceObservationsTest.class.getResourceAsStream("/reference/native-search-v2.json.gz"))){return in.readAllBytes();}
        catch(IOException failure){throw new UncheckedIOException(failure);}
    }
    private static Set<String> inputs(JsonNode root){
        Set<String> hashes=new HashSet<>();
        for(var row:root.path("sourcePayloads").properties())hashes.add(row.getValue().path("sha256").asString());
        for(var row:root.path("referenceSources"))hashes.add(row.path("sha256").asString());
        var canonical=root.path("canonicalObservations");hashes.add(canonical.path("contentSha256").asString());
        hashes.add(canonical.at("/producer/sha256").asString());hashes.add(canonical.at("/environment/projDatabase/sha256").asString());
        for(var frame:canonical.path("frames"))hashes.add(frame.path("coordinatesSha256").asString());
        return hashes;
    }
    private static PinnedRouteSearchGraph load(byte[] packet){return PinnedRouteSearchGraph.load(packet,new PinnedRouteSearchGraph.Pin(RouteSourceReceipt.sha256(packet),RAW,inputs(JSON.readTree(packet))),JSON);}
    private static void reseal(JsonNode root){
        var canonical=(ObjectNode)root.path("canonicalObservations");
        for(var frame:canonical.path("frames")){
            var coordinates=JSON.createObjectNode();coordinates.set("sourcePoints",frame.path("sourcePoints"));coordinates.set("sourceVisits",frame.path("sourceVisits"));
            ((ObjectNode)frame).put("coordinatesSha256",CanonicalSourceObservations.canonicalSha(coordinates));
        }
        canonical.remove("contentSha256");canonical.put("contentSha256",CanonicalSourceObservations.canonicalSha(canonical));
    }
    @Test void actual_java_full_search_consumes_all_source_canonical_frames_without_a_selected_path_input()throws IOException {
        var packet=bytes();assertThat(RouteSourceReceipt.sha256(packet)).isEqualTo("f3d92ca776f7a60043b0f087b49a815c333930cf71138a70494c43d948daf503");
        var graph=load(packet);var source=SourceRoleNativeGeneratorTest.geometry(graph);
        assertThat(graph.usesCanonicalObservations()).isTrue();
        var result=new SourceRoleNativeGenerator(SourceRoleNativeGenerator.Config.frozenV3()).generate(source,SourceRoleNativeGeneratorTest.facts(source,graph),graph,63,64);
        assertThat(result.status()).isEqualTo("CANDIDATE_ONLY");assertThat(result.candidate().orElseThrow().points()).hasSize(157);
        assertThat(result.inputDigest()).isEqualTo(RouteDisplayGeneration.input(source).digest());assertThat(result.published()).isFalse();
        var out=Path.of("build/reports/canonical-java-native-search-replay.json");Files.createDirectories(out.getParent());Files.writeString(out,JSON.writeValueAsString(result));
    }
    @Test void a_re_pinned_packet_cannot_ignore_a_changed_derived_checksum(){
        var root=JSON.readTree(bytes());((ObjectNode)root.at("/canonicalObservations/frames/0/sourcePoints/0/point")).put("x",1);
        assertThatThrownBy(()->load(JSON.writeValueAsBytes(root))).hasMessage("CANONICAL_CONTENT_HASH_MISMATCH");
    }
    @Test void re_sealed_packets_still_require_every_identity_frame_and_independent_projection(){
        List<Consumer<JsonNode>> changes=List.of(
            root->{var p=(ObjectNode)root.at("/canonicalObservations/frames/0/sourcePoints/0/point");p.put("x",p.path("x").asDouble()+.01);},
            root->((ObjectNode)root.at("/canonicalObservations/frames/0/sourceVisits/0")).put("stationId","wrong-station"),
            root->((ArrayNode)root.at("/canonicalObservations/frames/1/sourcePoints")).remove(100),
            root->((ObjectNode)root.at("/canonicalObservations/frames/0/sourcePoints/1")).put("sourcePointIndex",0),
            root->((ObjectNode)root.at("/canonicalObservations/frames/0")).put("horizontalUnit","DEGREE"),
            root->((ObjectNode)root.at("/canonicalObservations/frames/0")).put("crsAuthority","EPSG:5186"),
            root->((ObjectNode)root.at("/canonicalObservations/frames/0/sourcePoints/0/sourcePoint")).put("x",127.5));
        for(var change:changes){var root=JSON.readTree(bytes());change.accept(root);reseal(root);
            assertThatThrownBy(()->load(JSON.writeValueAsBytes(root))).isInstanceOf(IllegalArgumentException.class);}
    }
    @Test void independently_pinned_model_inputs_must_include_the_derived_source_revision(){
        var packet=bytes();var root=JSON.readTree(packet);var hashes=inputs(root);hashes.remove(root.at("/canonicalObservations/contentSha256").asString());
        assertThatThrownBy(()->PinnedRouteSearchGraph.load(packet,new PinnedRouteSearchGraph.Pin(RouteSourceReceipt.sha256(packet),RAW,hashes),JSON)).hasMessage("MODEL_INPUT_PIN_MISMATCH");
    }
}
