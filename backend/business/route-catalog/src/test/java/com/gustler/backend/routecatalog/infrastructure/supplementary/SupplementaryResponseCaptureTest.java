package com.gustler.backend.routecatalog.infrastructure.supplementary;

import static org.assertj.core.api.Assertions.*;

import com.gustler.backend.routecatalog.domain.geometry.RouteSourceArchive;
import com.gustler.backend.routecatalog.infrastructure.jdbc.JdbcRouteSourceArchive;
import com.gustler.backend.routegeometry.api.RouteSourceReceipt;
import com.gustler.backend.routegeometry.api.RouteSourceReceipt.Status;
import com.gustler.backend.routegeometry.api.RouteDisplayInput.Point;
import com.gustler.backend.support.PostgresTestContainer;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes=SupplementaryResponseCaptureTest.Config.class)
class SupplementaryResponseCaptureTest {
    @Configuration(proxyBeanMethods=false)
    @EnableAutoConfiguration
    @Import({PostgresTestContainer.class, JdbcRouteSourceArchive.class})
    static class Config { @Bean ObjectMapper mapper() { return new ObjectMapper(); } }
    @Autowired JdbcClient jdbc;
    @Autowired RouteSourceArchive archive;
    @Autowired ObjectMapper json;
    private static final Clock CLOCK=Clock.fixed(Instant.parse("2026-11-02T00:00:00.123456789Z"),ZoneOffset.UTC);
    private static final String KEY="test-supplementary-secret-492";
    private static final byte[] BMS=("{\n \"TBBMSNODEM\":[{\"head\":[{\"list_total_count\":2},{\"RESULT\":{\"CODE\":\"INFO-000\"}}]},"
        +"{\"row\":[{\"NODE_ID\":\"1\",\"LAT\":3745.674,\"LOGT\":12702.382},"
        +"{\"NODE_ID\":\"2\",\"LAT\":3745.674,\"LOGT\":12702.497}]}]}\n").getBytes(StandardCharsets.UTF_8);

    @BeforeEach void reset() {
        jdbc.sql("TRUNCATE route_geometry_source_cache,route_source_blob,route_source_receipt,route_source_manifest CASCADE").update();
    }
    private byte[] web() {
        String row1="{\"seq\":1,\"type\":1,\"lon\":14137575.330745745,\"lat\":4439106.787250586}";
        String row2="{\"seq\":2,\"type\":3,\"lon\":14138688.525653679,\"lat\":4440500.749219745}";
        return ("{\n\"success\":true,\"result\":{\"gg\":{\"upLine\":{\"count\":2,\"list\":["+row1+","+row2+"]},"
            +"\"downLine\":{\"count\":2,\"list\":["+row1+","+row2+"]}}}}\n").getBytes(StandardCharsets.UTF_8);
    }
    private HttpServer server(int status,byte[] bytes) throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/source",exchange -> {
            exchange.getResponseHeaders().add("Content-Type","application/json; charset=utf-8");
            exchange.sendResponseHeaders(status,bytes.length);
            try(var body=exchange.getResponseBody()) { body.write(bytes); }
        });server.start();return server;
    }
    private URI url(HttpServer server) { return URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/source"); }
    private GgBmsNodeGeometrySource bms(BoundedJsonHttp.Fetch fetch) {
        return new GgBmsNodeGeometrySource(json,jdbc,CLOCK,KEY,fetch,archive);
    }
    private int blobs() { return jdbc.sql("SELECT count(*) FROM route_source_blob").query(Integer.class).single(); }

    @Test void real_HTTP_web_success_preserves_original_bytes_and_safe_metadata() throws Exception {
        byte[] original=web();var server=server(200,original);
        try {
            var client=new BoundedJsonHttp();
            var source=new GbisWebGeometrySource(json,CLOCK,ignored -> client.getWithMetadata(url(server)),true);
            var contribution=source.read("204000057");
            assertThat(contribution.shape()).isPresent();
            var receipt=contribution.receipts().getFirst();
            assertThat(receipt.status()).isEqualTo(Status.SUCCESS);assertThat(receipt.httpStatus()).isEqualTo(200);
            assertThat(receipt.acquiredAt()).isEqualTo(CLOCK.instant());assertThat(receipt.providerVersion()).isEqualTo("NOT_PROVIDED");
            assertThat(receipt.coordinateSystem()).isEqualTo(RouteSourceReceipt.CoordinateSystem.WEB_MERCATOR_ASSUMED);
            String manifest=archive.record("ROUTE_ACQUISITION",contribution.receipts(),List.of());
            assertThat(archive.restore(manifest).receipts().getFirst().body()).containsExactly(original);
            assertThat(receipt.toString()).doesNotContain("https://",new String(original,StandardCharsets.UTF_8));
        } finally {server.stop(0);}
    }

    @Test void real_HTTP_errors_discard_credential_echo_before_receipt_storage() throws Exception {
        var server=server(503,("{\"error\":\"https://provider.invalid?KEY="+KEY+"\"}").getBytes(StandardCharsets.UTF_8));
        try {
            var client=new BoundedJsonHttp();var response=client.getWithMetadata(url(server));
            assertThat(response.httpStatus()).isEqualTo(503);assertThat(response.body()).isNull();
            var source=new GbisWebGeometrySource(json,CLOCK,ignored -> response,true);
            var contribution=source.read("204000057");assertThat(contribution.shape()).isEmpty();
            var receipt=contribution.receipts().getFirst();assertThat(receipt.status()).isEqualTo(Status.HTTP_ERROR);
            String manifest=archive.record("ROUTE_ACQUISITION",contribution.receipts(),List.of());
            var restored=archive.restore(manifest);assertThat(restored.receipts().getFirst().body()).isNull();
            assertThat(restored.document()).doesNotContain(KEY,"https://","provider.invalid");assertThat(blobs()).isZero();
        } finally {server.stop(0);}
    }

    @Test void malformed_and_sensitive_web_bodies_are_not_retained() {
        var invalid=new GbisWebGeometrySource(json,CLOCK,ignored -> new BoundedJsonHttp.Result(200,"{\"success\":false}".getBytes(StandardCharsets.UTF_8)),true);
        assertThat(invalid.read("204000057").receipts().getFirst().status()).isEqualTo(Status.INVALID_RESPONSE);
        String sensitive=new String(web(),StandardCharsets.UTF_8).replace("\"success\":true","\"success\":true,\"extra\":{\"\\u0074oken\":\""+KEY+"\"}");
        var source=new GbisWebGeometrySource(json,CLOCK,ignored -> new BoundedJsonHttp.Result(200,sensitive.getBytes(StandardCharsets.UTF_8)),true);
        var contribution=source.read("204000057");assertThat(contribution.shape()).isPresent();
        var receipt=contribution.receipts().getFirst();assertThat(receipt.status()).isEqualTo(Status.SENSITIVE_RESPONSE);
        assertThat(receipt.body()).isNull();assertThat(receipt.bodySha256()).isNull();
        assertThat(contribution.source().digest()).isNotEqualTo(RouteSourceReceipt.sha256(sensitive.getBytes(StandardCharsets.UTF_8)));
        String id=archive.record("ROUTE_ACQUISITION",contribution.receipts(),List.of());
        assertThat(archive.restore(id).document()).doesNotContain(KEY);assertThat(blobs()).isZero();
    }

    @Test void common_BMS_snapshot_is_reused_without_per_route_fetch_or_blob_copy_and_restores_after_restart() throws Exception {
        var requests=new AtomicInteger();var server=server(200,BMS);
        try {
            var client=new BoundedJsonHttp();var source=bms(ignored -> {requests.incrementAndGet();return client.getWithMetadata(url(server));});
            source.refresh();var first=source.read("204000057");var other=source.read("204000081");
            assertThat(requests.get()).isEqualTo(1);assertThat(first.nodes()).hasSize(2);
            assertThat(first.reusedManifestIds()).hasSize(1).isEqualTo(other.reusedManifestIds());
            String snapshot=first.reusedManifestIds().getFirst();assertThat(archive.restore(snapshot).receipts().getFirst().body()).containsExactly(BMS);
            archive.record("ROUTE_ACQUISITION",first.receipts(),first.reusedManifestIds());
            archive.record("ROUTE_ACQUISITION",other.receipts(),other.reusedManifestIds());assertThat(blobs()).isEqualTo(2);
            assertThat(archive.restore(snapshot).receipts()).extracting(RouteSourceReceipt::status)
                .containsExactly(Status.SUCCESS,Status.DERIVED);
            var restarted=bms(ignored -> {throw new AssertionError("read cannot refetch");});
            assertThat(restarted.read("204000057").nodes()).containsExactlyElementsOf(first.nodes());
            assertThat(restarted.read("204000057").reusedManifestIds()).containsExactly(snapshot);
        } finally {server.stop(0);}
    }

    @Test void failed_later_BMS_page_is_archived_without_replacing_the_previous_usable_snapshot() {
        var source=bms(ignored -> new BoundedJsonHttp.Result(200,BMS));source.refresh();
        var prior=source.read("204000057");String oldManifest=prior.reusedManifestIds().getFirst();
        StringBuilder page=new StringBuilder("{\"TBBMSNODEM\":[{\"head\":[{\"list_total_count\":1001},{\"RESULT\":{\"CODE\":\"INFO-000\"}}]},{\"row\":[");
        for(int i=0;i<1000;i++){if(i>0)page.append(',');page.append("{\"NODE_ID\":\"").append(i).append("\",\"LAT\":3745.674,\"LOGT\":12702.382}");}
        page.append("]}]}");var calls=new AtomicInteger();
        var refresh=bms(ignored -> calls.incrementAndGet()==1 ? new BoundedJsonHttp.Result(200,page.toString().getBytes(StandardCharsets.UTF_8))
                : new BoundedJsonHttp.Result(503,null));
        refresh.refresh();var retained=refresh.read("204000057");assertThat(retained.nodes()).containsExactlyElementsOf(prior.nodes());
        assertThat(retained.reusedManifestIds()).hasSize(2).contains(oldManifest);
        String failed=retained.reusedManifestIds().stream().filter(id -> !id.equals(oldManifest)).findFirst().orElseThrow();
        assertThat(archive.restore(failed).receipts()).extracting(RouteSourceReceipt::status).containsExactly(Status.SUCCESS,Status.HTTP_ERROR);
        assertThat(archive.restore(failed).receipts().getLast().body()).isNull();
        assertThat(archive.restore(failed).receipts()).noneMatch(r -> r.status() == Status.DERIVED);
        assertThat(jdbc.sql("SELECT acquisition_manifest_id FROM route_geometry_source_cache").query(String.class).single()).isEqualTo(oldManifest);
    }

    @Test void cache_tampering_is_rejected_even_when_mutable_cache_digest_is_recomputed() {
        var source=bms(ignored -> new BoundedJsonHttp.Result(200,BMS));source.refresh();
        var changed=List.of(new Point(128,37),new Point(128.01,37));String text=json.writeValueAsString(changed);
        jdbc.sql("UPDATE route_geometry_source_cache SET document=CAST(? AS jsonb)").param(text).update();
        assertThat(bms(ignored -> {throw new AssertionError();}).read("204000057").source().status()).isEqualTo("UNAVAILABLE");
        jdbc.sql("UPDATE route_geometry_source_cache SET content_digest=?").param(RouteSourceReceipt.sha256(text.getBytes(StandardCharsets.UTF_8))).update();
        var blocked=bms(ignored -> {throw new AssertionError();}).read("204000057");
        assertThat(blocked.nodes()).isEmpty();assertThat(blocked.receipts().getFirst().status()).isEqualTo(Status.INVALID_RESPONSE);
    }

    @Test void sensitive_BMS_success_keeps_only_normalized_nodes_and_an_explicit_withheld_receipt() {
        byte[] sensitive=new String(BMS,StandardCharsets.UTF_8).replace("\"TBBMSNODEM\":","\"echo\":\""+KEY+"\",\"TBBMSNODEM\":").getBytes(StandardCharsets.UTF_8);
        var source=bms(ignored -> new BoundedJsonHttp.Result(200,sensitive));source.refresh();var read=source.read("204000057");
        assertThat(read.nodes()).hasSize(2);String id=read.reusedManifestIds().getFirst();
        var receipt=archive.restore(id).receipts().getFirst();assertThat(receipt.status()).isEqualTo(Status.SENSITIVE_RESPONSE);
        assertThat(receipt.body()).isNull();assertThat(receipt.bodySha256()).isNull();assertThat(blobs()).isEqualTo(1);
        assertThat(receipt.toString()).doesNotContain(KEY);
        var derived=archive.restore(id).receipts().getLast();
        assertThat(derived.status()).isEqualTo(Status.DERIVED);
        assertThat(archive.restore(id).receipts()).noneMatch(r -> r.status()==Status.SUCCESS);
        assertThat(derived.operation()).isEqualTo("NORMALIZED_NODE_SNAPSHOT_V1");
        assertThat(derived.httpStatus()).isNull();
        assertThat(derived.coordinateSystem()).isEqualTo(RouteSourceReceipt.CoordinateSystem.WGS84_ASSUMED);
        assertThat(new String(derived.body(),StandardCharsets.UTF_8)).doesNotContain(KEY);
        assertThat(bms(ignored -> {throw new AssertionError();}).read("204000057").nodes()).hasSize(2);
    }

    @Test void sensitive_raw_page_cannot_hide_a_consistently_tampered_mutable_normalized_cache() {
        byte[] sensitive=new String(BMS,StandardCharsets.UTF_8).replace("\"TBBMSNODEM\":","\"echo\":\""+KEY+"\",\"TBBMSNODEM\":").getBytes(StandardCharsets.UTF_8);
        var source=bms(ignored -> new BoundedJsonHttp.Result(200,sensitive));source.refresh();
        String manifest=source.read("204000057").reusedManifestIds().getFirst();
        String changed=json.writeValueAsString(List.of(new Point(128,37),new Point(128.01,37)));
        jdbc.sql("UPDATE route_geometry_source_cache SET document=CAST(? AS jsonb),content_digest=?")
            .params(changed,RouteSourceReceipt.sha256(changed.getBytes(StandardCharsets.UTF_8))).update();
        var restarted=bms(ignored -> {throw new AssertionError();}).read("204000057");
        assertThat(restarted.nodes()).isEmpty();
        assertThat(restarted.receipts().getFirst().status()).isEqualTo(Status.INVALID_RESPONSE);
        var originals=archive.restore(manifest).receipts().stream().filter(r -> r.status()!=Status.DERIVED).toList();
        assertThat(originals).hasSize(1);
        assertThat(originals.getFirst().status()).isEqualTo(Status.SENSITIVE_RESPONSE);
        assertThat(originals.getFirst().body()).isNull();assertThat(originals.getFirst().bodySha256()).isNull();
    }

    @Test void different_sensitive_normalized_inputs_at_the_same_time_have_different_immutable_manifests() {
        String base=new String(BMS,StandardCharsets.UTF_8).replace("\"TBBMSNODEM\":","\"echo\":\""+KEY+"\",\"TBBMSNODEM\":");
        var first=bms(ignored -> new BoundedJsonHttp.Result(200,base.getBytes(StandardCharsets.UTF_8)));first.refresh();
        String firstManifest=first.read("204000057").reusedManifestIds().getFirst();
        String changed=base.replace("12702.382","12703.382");
        var second=bms(ignored -> new BoundedJsonHttp.Result(200,changed.getBytes(StandardCharsets.UTF_8)));second.refresh();
        String secondManifest=second.read("204000057").reusedManifestIds().getFirst();
        assertThat(secondManifest).isNotEqualTo(firstManifest);
        var one=archive.restore(firstManifest).receipts();var two=archive.restore(secondManifest).receipts();
        assertThat(one.getFirst()).isEqualTo(two.getFirst()); // Both raw bodies are deliberately withheld.
        assertThat(one.getFirst().bodySha256()).isNull();assertThat(two.getFirst().bodySha256()).isNull();
        assertThat(one.getLast().status()).isEqualTo(Status.DERIVED);
        assertThat(one.getLast().bodySha256()).isNotEqualTo(two.getLast().bodySha256());
        assertThat(blobs()).isEqualTo(2); // Two normalized snapshots, zero sensitive upstream bodies.
    }

    @ParameterizedTest
    @ValueSource(strings={"MISSING","DUPLICATE","WRONG_OPERATION"})
    void a_full_manifest_requires_exactly_one_correctly_identified_derived_snapshot(String defect) {
        var source=bms(ignored -> new BoundedJsonHttp.Result(200,BMS));source.refresh();
        String good=source.read("204000057").reusedManifestIds().getFirst();
        var original=archive.restore(good).receipts();
        var changed=new java.util.ArrayList<>(original);
        var derived=original.getLast();
        switch(defect) {
            case "MISSING" -> changed.removeLast();
            case "DUPLICATE" -> changed.add(derived);
            case "WRONG_OPERATION" -> changed.set(changed.size()-1,new RouteSourceReceipt(derived.sourceId(),
                "OTHER_DERIVATION","SHARED",Status.DERIVED,null,derived.acquiredAt(),derived.providerVersion(),
                derived.coordinateSystem(),derived.body()));
            default -> throw new AssertionError();
        }
        String bad=archive.record("BMS_SNAPSHOT",changed,List.of());
        jdbc.sql("UPDATE route_geometry_source_cache SET acquisition_manifest_id=?").param(bad).update();
        var read=bms(ignored -> {throw new AssertionError();}).read("204000057");
        assertThat(read.nodes()).isEmpty();assertThat(read.receipts().getFirst().status()).isEqualTo(Status.INVALID_RESPONSE);
    }

    @Test void an_older_refresh_cannot_replace_a_newer_database_snapshot_in_local_memory() {
        var newest=new GgBmsNodeGeometrySource(json,jdbc,Clock.offset(CLOCK,Duration.ofHours(1)),KEY,
                ignored -> new BoundedJsonHttp.Result(200,BMS),archive);
        newest.refresh();var expected=newest.read("204000057");
        byte[] different=new String(BMS,StandardCharsets.UTF_8).replace("12702.382","12703.382").getBytes(StandardCharsets.UTF_8);
        var older=bms(ignored -> new BoundedJsonHttp.Result(200,different));older.refresh();
        // The older clock cannot serve future data; it reports stale, and never leaks its rejected candidate.
        assertThat(older.read("204000057").source().status()).isEqualTo("STALE");
        var restarted=new GgBmsNodeGeometrySource(json,jdbc,Clock.offset(CLOCK,Duration.ofHours(1)),KEY,
                ignored -> {throw new AssertionError();},archive);
        assertThat(restarted.read("204000057").nodes()).containsExactlyElementsOf(expected.nodes());
        assertThat(restarted.read("204000057").reusedManifestIds()).isEqualTo(expected.reusedManifestIds());
    }

    @Test void thrown_transport_messages_never_enter_archive_receipts() {
        var source=new GbisWebGeometrySource(json,CLOCK,ignored -> {throw new IllegalStateException("https://private.invalid?serviceKey="+KEY);},true);
        var contribution=source.read("204000057");assertThat(contribution.receipts().getFirst().status()).isEqualTo(Status.TRANSPORT_FAILURE);
        String id=archive.record("ROUTE_ACQUISITION",contribution.receipts(),List.of());
        assertThat(archive.restore(id).document()).doesNotContain(KEY,"private.invalid","https://");assertThat(blobs()).isZero();
    }
    private byte[] partlyInvalidBms() {
        return new String(BMS,StandardCharsets.UTF_8).replace("12702.497","12861.3").getBytes(StandardCharsets.UTF_8);
    }
    @Test void invalid_ddmm_is_quarantined_with_original_page_and_record_hash_without_repairing_the_coordinate() {
        byte[] original=partlyInvalidBms();var source=bms(ignored->new BoundedJsonHttp.Result(200,original));source.refresh();
        var contribution=source.read("204000057");assertThat(contribution.source().status()).isEqualTo("AVAILABLE_WITH_QUARANTINE");assertThat(contribution.nodes()).hasSize(1);
        String document=jdbc.sql("SELECT document::text FROM route_geometry_source_cache").query(String.class).single();
        var normalized=json.readValue(document,GgBmsNodeGeometrySource.NormalizedV2.class);
        assertThat(normalized.sourceRows()).isEqualTo(2);assertThat(normalized.points()).hasSize(1);assertThat(normalized.quarantined()).hasSize(1);
        var rejected=normalized.quarantined().getFirst();assertThat(rejected.nodeId()).isEqualTo("2");assertThat(rejected.sourceRecordIndex()).isEqualTo(1);
        assertThat(rejected.reason()).isEqualTo("BMS_MINUTES");assertThat(rejected.pageSha256()).isEqualTo(RouteSourceReceipt.sha256(original));
        assertThat(normalized.condition()).isEqualTo("POSITIVE_CORROBORATION_ONLY_NO_ABSENCE_INFERENCE");
        var manifest=archive.restore(contribution.reusedManifestIds().getFirst());assertThat(manifest.receipts().getFirst().body()).containsExactly(original);
        assertThat(manifest.receipts().getLast().operation()).isEqualTo("NORMALIZED_NODE_SNAPSHOT_V2");
        assertThat(bms(ignored->{throw new AssertionError();}).read("204000057").nodes()).isEqualTo(contribution.nodes());
    }
    @Test void a_consistently_rehashed_mutable_quarantine_cache_still_must_match_its_immutable_derived_receipt() {
        var source=bms(ignored->new BoundedJsonHttp.Result(200,partlyInvalidBms()));source.refresh();
        var original=json.readValue(jdbc.sql("SELECT document::text FROM route_geometry_source_cache").query(String.class).single(),GgBmsNodeGeometrySource.NormalizedV2.class);
        var point=original.points().getFirst();var changed=new GgBmsNodeGeometrySource.NormalizedV2(original.format(),original.sourceRows(),
            List.of(new Point(point.x()+.01,point.y())),original.quarantined(),true,true,original.condition());
        String text=json.writeValueAsString(changed);jdbc.sql("UPDATE route_geometry_source_cache SET document=CAST(? AS jsonb),content_digest=?").params(text,RouteSourceReceipt.sha256(text.getBytes(StandardCharsets.UTF_8))).update();
        var read=bms(ignored->{throw new AssertionError();}).read("204000057");assertThat(read.nodes()).isEmpty();assertThat(read.receipts().getFirst().status()).isEqualTo(Status.INVALID_RESPONSE);
    }
    @Test void duplicate_identity_across_invalid_and_valid_rows_rejects_the_whole_acquisition() {
        String text=new String(BMS,StandardCharsets.UTF_8).replace("12702.382","12861.3").replace("\"NODE_ID\":\"2\"","\"NODE_ID\":\"1\"");
        var source=bms(ignored->new BoundedJsonHttp.Result(200,text.getBytes(StandardCharsets.UTF_8)));source.refresh();
        assertThat(jdbc.sql("SELECT count(*) FROM route_geometry_source_cache").query(Integer.class).single()).isZero();assertThat(source.read("204000057").nodes()).isEmpty();
    }

}
