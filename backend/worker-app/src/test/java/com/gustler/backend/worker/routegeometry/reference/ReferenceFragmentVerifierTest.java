package com.gustler.backend.worker.routegeometry.reference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.routegeometry.api.RouteShapeEvidence;
import com.gustler.backend.routegeometry.engine.api.RouteDisplayGeneration;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry;
import com.gustler.backend.routegeometry.engine.api.accuracy.AccuracyEvidence.FeatureKind;
import com.gustler.backend.worker.routegeometry.reference.ReferenceFragmentVerifier.Position;
import com.gustler.backend.worker.routegeometry.reference.ReferenceFragmentVerifier.RequestedScope;
import com.gustler.backend.worker.routegeometry.reference.ReferenceFragmentVerifier.SourceRange;
import com.gustler.backend.worker.routegeometry.reference.ReferenceFragmentVerifier.SourceWindow;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Fixed source/model-output/native catalog examples; no evaluation answer or ground-truth input is loaded. */
class ReferenceFragmentVerifierTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String ROOT="/reference/ngii-7007-1-v3/";
    private static final String CATALOG_SHA="0116878e564b86707ac423f0183e40a350942384b692c48b0bda5f754726ed39";
    private static final String ORIGINAL_CATALOG_SHA="4058867f71541ecdc85df658492ab912830b945535ffc0eb70b4c54d51b9f804";
    private static final String ARCHIVE_SHA="4875b3f8b5408387456f8e769ade49c906fce6a2d21f4dfa0ecca1a4f7e189ce";
    private static final String SOURCE_SHA="3898bf3e38e664c13ebbb81f3bf716e8bb2522a43ae54644ddc843b826b64aa9";
    private static final String CANDIDATE_SHA="d6fb70022ce9e7dee246e181a0700d2c6dc0730952dd18a541cb83fda71a2048";
    private static final String INPUT_DIGEST="afc0e641633918c515a4526d16385210466977b5f7147c2f4c25dc8d81067b05";
    private final ReferenceFragmentVerifier verifier=new ReferenceFragmentVerifier(JSON);

    private static byte[] resource(String name) {
        try(var input=ReferenceFragmentVerifierTest.class.getResourceAsStream(ROOT+name)) {
            if(input==null) throw new IllegalStateException("Missing fixed fixture: "+name);
            return input.readAllBytes();
        } catch(IOException failure) { throw new UncheckedIOException(failure); }
    }
    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static PinnedNativeCatalog.Pin pin() {
        // The caller supplies this independent pin. No trusted digest is read from the model packet.
        return new PinnedNativeCatalog.Pin(CATALOG_SHA,ORIGINAL_CATALOG_SHA,Set.of(ARCHIVE_SHA));
    }
    private static PinnedNativeCatalog catalog() {
        return PinnedNativeCatalog.load(resource("catalog-slice.json"),pin(),JSON);
    }
    private static RequestedScope requested() {
        return new RequestedScope(new SourceWindow(831,835),
            new SourceRange(new Position(831,0),new Position(834,1)),List.of(63,64));
    }
    private static RouteGeometry geometry() {
        var raw=JSON.readTree(resource("source.json"));
        var points=new ArrayList<RouteGeometry.Point>();
        for(var point:raw.path("points")) points.add(point(point));
        var stops=new ArrayList<RouteGeometry.Stop>();
        for(var stop:raw.path("busStops")) stops.add(new RouteGeometry.Stop(stop.path("stationId").asString(),
            stop.path("stopSequence").asInt(),stop.path("name").asString(),stop.path("direction").asString(),
            stop.path("boardingAllowed").asBoolean(),stop.path("point").isNull()?null:point(stop.path("point"))));
        return new RouteGeometry(raw.path("routeId").asString(),Long.parseLong(raw.path("referenceVersionId").asString()),
            raw.path("name").asString(),raw.path("startPoint").asString(),raw.path("endPoint").asString(),
            raw.at("/turningPoint/stopSequence").isNull()?null:raw.at("/turningPoint/stopSequence").asInt(),stops,
            new RouteGeometry.Shape(UUID.fromString("00000000-0000-0000-0000-000000000001"),Instant.EPOCH,points,RouteShapeEvidence.EMPTY));
    }
    private static RouteGeometry.Point point(JsonNode value) {
        return new RouteGeometry.Point(value.path("x").asDouble(),value.path("y").asDouble());
    }
    private ReferenceFragmentVerifier.VerifiedFragment verify(byte[] candidate) {
        return verifier.verify(geometry(),resource("source.json"),requested(),candidate,catalog());
    }
    private static void set(JsonNode node,String pointer,Object value) {
        int slash=pointer.lastIndexOf('/');var parent=node.at(pointer.substring(0,slash));String key=pointer.substring(slash+1);
        JsonNode replacement=JSON.valueToTree(value);
        if(parent instanceof ArrayNode array) array.set(Integer.parseInt(key),replacement);
        else ((ObjectNode)parent).set(key,replacement);
    }

    @Test void fixed_model_fragment_matches_original_native_clipping_and_python_projection_values() {
        assertThat(sha(resource("candidate.json"))).isEqualTo(CANDIDATE_SHA);
        assertThat(sha(resource("source.json"))).isEqualTo(SOURCE_SHA);
        assertThat(sha(resource("catalog-slice.json"))).isEqualTo(CATALOG_SHA);
        assertThat(RouteDisplayGeneration.input(geometry()).digest()).isEqualTo(INPUT_DIGEST);
        var candidate=JSON.readTree(resource("candidate.json"));var result=verify(resource("candidate.json"));
        assertThat(result.fragmentOnly()).isTrue();assertThat(result.published()).isFalse();
        assertThat(result.accuracyGrade()).isEqualTo("UNEVALUATED");assertThat(result.associationStatus()).isEqualTo("UNKNOWN");
        assertThat(result.sourceProjectionTraceVerified()).isTrue();
        assertThat(result.points()).hasSize(157);assertThat(result.nativeCoordinates()).hasSize(157);
        for(int i=0;i<result.points().size();i++) {
            var expected=candidate.at("/displayFragment/points").get(i);
            assertThat(result.points().get(i).x()).isCloseTo(expected.path("x").asDouble(),org.assertj.core.data.Offset.offset(1e-8));
            assertThat(result.points().get(i).y()).isCloseTo(expected.path("y").asDouble(),org.assertj.core.data.Offset.offset(1e-8));
            var nativeExpected=candidate.path("nativeCoordinates").get(i);var actual=result.nativeCoordinates().get(i);
            assertThat(actual.x()).isCloseTo(nativeExpected.get(0).asDouble(),org.assertj.core.data.Offset.offset(1e-5));
            assertThat(actual.y()).isCloseTo(nativeExpected.get(1).asDouble(),org.assertj.core.data.Offset.offset(1e-5));
            assertThat(actual.z()).isCloseTo(nativeExpected.get(2).asDouble(),org.assertj.core.data.Offset.offset(1e-8));
        }
        assertThat(result.operationStatus()).isEqualTo("UNKNOWN");assertThat(result.entryExitStatus()).isEqualTo("UNKNOWN");
        assertThat(result.visits()).allSatisfy(v->assertThat(v.verificationLevel()).isEqualTo("STRUCTURAL_ONLY"));
        assertThat(result.visits()).extracting(v->v.sequence()).containsExactly(63,64);
        assertThat(result.visits()).extracting(v->v.stationId()).containsExactly("119000289","119000288");
        assertThat(result.visits().getFirst().pathConnection().segmentIndex()).isEqualTo(0);
        assertThat(result.visits().getLast().pathConnection().segmentIndex()).isEqualTo(155);
    }
    @Test void output_verified_or_adoption_flags_cannot_promote_a_fragment() {
        var candidate=JSON.readTree(resource("candidate.json"));
        for(String field:List.of("/published","/modelAdopted","/displayFragment/published",
                "/displayFragment/entryExitVerified","/displayFragment/operationScopeVerified",
                "/displayFragment/sourceOccurrenceTraceVerified")) set(candidate,field,true);
        set(candidate,"/displayFragment/sourceOccurrenceTraceVerified",false);
        var result=verify(JSON.writeValueAsBytes(candidate));
        assertThat(result.accuracyGrade()).isEqualTo("UNEVALUATED");assertThat(result.fragmentOnly()).isTrue();
        assertThat(result.published()).isFalse();assertThat(result.associationStatus()).isEqualTo("UNKNOWN");
        assertThat(result.sourceProjectionTraceVerified()).isTrue();
    }
    private record Perturbation(String name,Consumer<JsonNode> change) {}
    @TestFactory Stream<DynamicTest> changed_native_output_scope_or_visit_fails_closed() {
        return Stream.of(
            new Perturbation("native X on both model arrays",n->{
                double x=n.at("/nativeCoordinates/10/0").asDouble()+0.1;
                set(n,"/nativeCoordinates/10/0",x);set(n,"/displayFragment/nativeCoordinatesXYZ/10/0",x);}),
            new Perturbation("native height on both arrays",n->{
                double z=n.at("/nativeCoordinates/10/2").asDouble()+1;
                set(n,"/nativeCoordinates/10/2",z);set(n,"/displayFragment/nativeCoordinatesXYZ/10/2",z);}),
            new Perturbation("projected longitude",n->set(n,"/displayFragment/points/10/x",n.at("/displayFragment/points/10/x").asDouble()+0.0001)),
            new Perturbation("connection segment",n->set(n,"/displayFragment/visits/0/pathConnection/segmentIndex",2)),
            new Perturbation("connection fraction",n->set(n,"/displayFragment/visits/0/pathConnection/segmentFraction",0.25)),
            new Perturbation("connection coordinate",n->set(n,"/displayFragment/visits/0/pathConnection/point/x",127.1)),
            new Perturbation("wrong native CRS",n->set(n,"/displayFragment/nativeCoordinateSystem","EPSG:5186")),
            new Perturbation("source projection trace point",n->set(n,"/displayFragment/sourceOccurrenceTrace/1/point/0",954000)),
            new Perturbation("source projection trace index",n->set(n,"/displayFragment/sourceOccurrenceTrace/1/sourcePointIndex",840)),
            new Perturbation("source projection trace progress",n->set(n,"/displayFragment/sourceOccurrenceTrace/1/progressMeters",300)),
            new Perturbation("wrong source bytes SHA",n->set(n,"/rawSha256","0".repeat(64))),
            new Perturbation("wrong exact input digest",n->set(n,"/inputDigest","0".repeat(64))),
            new Perturbation("source window escapes request",n->{set(n,"/sourceWindow/startPointIndex",830);set(n,"/displayFragment/sourceWindow/startPointIndex",830);}),
            new Perturbation("source fraction changes request",n->{set(n,"/sourceRange/start/segmentFraction",0.5);set(n,"/displayFragment/sourceRange/start/segmentFraction",0.5);}),
            new Perturbation("required visit identity",n->{set(n,"/visits/0/stationId","119999999");set(n,"/displayFragment/visits/0/stationId","119999999");}),
            new Perturbation("visit direction",n->{set(n,"/visits/0/direction","UP");set(n,"/displayFragment/visits/0/direction","UP");}),
            new Perturbation("unresolved edge id",n->{set(n,"/directedLinks/0","unknown:edge");set(n,"/displayFragment/directedLinks/0","unknown:edge");})
        ).map(p->DynamicTest.dynamicTest(p.name(),()->{
            var changed=JSON.readTree(resource("candidate.json"));p.change().accept(changed);
            assertThatThrownBy(()->verify(JSON.writeValueAsBytes(changed))).isInstanceOf(IllegalArgumentException.class);
        }));
    }
    @Test void server_scope_required_visits_cannot_be_selected_by_the_packet() {
        var different=new RequestedScope(new SourceWindow(831,835),new SourceRange(new Position(831,0),new Position(834,1)),List.of(63,65));
        assertThatThrownBy(()->verifier.verify(geometry(),resource("source.json"),different,resource("candidate.json"),catalog()))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void server_source_changes_cannot_reuse_an_old_fragment() {
        var server=geometry();var stops=new ArrayList<>(server.busStops());var old=stops.get(62);
        stops.set(62,new RouteGeometry.Stop(old.stationId(),old.stopSequence(),old.name(),old.direction(),old.boardingAllowed(),
            new RouteGeometry.Point(old.point().x()+0.00001,old.point().y())));
        var changed=new RouteGeometry(server.routeId(),server.referenceVersionId(),server.name(),server.startPoint(),server.endPoint(),server.turnSequence(),stops,server.shape());
        assertThatThrownBy(()->verifier.verify(changed,resource("source.json"),requested(),resource("candidate.json"),catalog()))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void trusted_catalog_pin_is_server_controlled_and_selected_features_are_immutable() {
        var bytes=resource("catalog-slice.json");
        assertThatThrownBy(()->PinnedNativeCatalog.load(bytes,new PinnedNativeCatalog.Pin("0".repeat(64),ORIGINAL_CATALOG_SHA,Set.of(ARCHIVE_SHA)),JSON))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("REFERENCE_HASH_MISMATCH");
        assertThatThrownBy(()->PinnedNativeCatalog.load(bytes,new PinnedNativeCatalog.Pin(CATALOG_SHA,"0".repeat(64),Set.of(ARCHIVE_SHA)),JSON))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->PinnedNativeCatalog.load(bytes,new PinnedNativeCatalog.Pin(CATALOG_SHA,ORIGINAL_CATALOG_SHA,Set.of("0".repeat(64))),JSON))
            .isInstanceOf(IllegalArgumentException.class);
        var catalog=catalog();assertThat(catalog.bundleDigest()).isEqualTo(CATALOG_SHA);
        assertThat(catalog.find("unresolved:edge")).isEmpty();
        var id=JSON.readTree(resource("candidate.json")).path("directedLinks").get(0).asString();
        var feature=catalog.find(id).orElseThrow();assertThat(feature.kind()).isEqualTo(FeatureKind.LANE);
        assertThat(feature.crs().code()).isEqualTo(5179);assertThat(feature.vertices()).hasSize(74);
        assertThatThrownBy(()->feature.vertices().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void malformed_or_oversized_protocols_do_not_open_external_paths() {
        var external=JSON.readTree(resource("candidate.json"));set(external,"/referencePath","/tmp/untrusted-catalog.json");
        assertThatThrownBy(()->verify(JSON.writeValueAsBytes(external))).isInstanceOf(IllegalArgumentException.class);
        var url=JSON.readTree(resource("candidate.json"));set(url,"/displayFragment/limits/0","https://example.invalid/native.json");
        assertThatThrownBy(()->verify(JSON.writeValueAsBytes(url))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("EXTERNAL_REFERENCE_FORBIDDEN");
        String duplicate="{\"inputDigest\":\""+"0".repeat(64)+"\","+new String(resource("candidate.json"),StandardCharsets.UTF_8).substring(1);
        assertThatThrownBy(()->verify(duplicate.getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->verify(new byte[1_000_001])).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("JSON_TOO_LARGE");
        assertThatThrownBy(()->verifier.verify(geometry(),new byte[4_000_001],requested(),resource("candidate.json"),catalog()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("JSON_TOO_LARGE");
        assertThatThrownBy(()->PinnedNativeCatalog.load(new byte[8_000_001],pin(),JSON))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("JSON_TOO_LARGE");
        assertThatThrownBy(()->verify("{broken}".getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
    }
}
