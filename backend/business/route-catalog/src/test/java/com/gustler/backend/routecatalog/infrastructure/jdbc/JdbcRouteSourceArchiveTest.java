package com.gustler.backend.routecatalog.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.*;

import com.gustler.backend.routecatalog.domain.geometry.RouteSourceArchive;
import com.gustler.backend.routegeometry.api.RouteDisplayInput;
import com.gustler.backend.routegeometry.api.RouteShapeEvidence;
import com.gustler.backend.routegeometry.api.RouteSourceReceipt;
import com.gustler.backend.routegeometry.api.RouteSourceReceipt.CoordinateSystem;
import com.gustler.backend.routegeometry.api.RouteSourceReceipt.Status;
import com.gustler.backend.support.PostgresTestContainer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Collections;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = JdbcRouteSourceArchiveTest.Config.class)
@ExtendWith(OutputCaptureExtension.class)
class JdbcRouteSourceArchiveTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({PostgresTestContainer.class, JdbcRouteSourceArchive.class})
    static class Config {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    }
    @Autowired RouteSourceArchive archive;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired DataSource dataSource;
    private static final Instant TIME = Instant.parse("2026-11-02T00:00:00.123456789Z");
    private static final byte[] BODY = "{\n  \"name\": \"정류장\", \"x\": 127.1000\n}\n".getBytes(StandardCharsets.UTF_8);
    private long routeId;
    private long versionId;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE route,route_source_blob,route_source_receipt,route_source_manifest CASCADE").update();
        routeId = jdbc.sql("""
            INSERT INTO route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name)
            VALUES ('204000057','GBIS','204000057','3330','A','B') RETURNING id
            """).query(Long.class).single();
        versionId = jdbc.sql("INSERT INTO route_version(route_id,content_digest,valid_from) VALUES (?,?,'2026-11-01T00:00:00Z') RETURNING id")
            .params(routeId, "a".repeat(64)).query(Long.class).single();
    }

    private RouteSourceReceipt success(String source, String operation, String scope, Instant at) {
        return new RouteSourceReceipt(source, operation, scope, Status.SUCCESS, 200, at,
            "20261102", CoordinateSystem.WGS84_ASSUMED, BODY);
    }
    private String capture() {
        return archive.record("ROUTE_SHAPE", List.of(success("GBIS_API", "SHAPE", "204000057", TIME)), List.of());
    }
    private RouteDisplayInput input() {
        return new RouteDisplayInput("204000057", null,
            List.of(new RouteDisplayInput.Point(127,37), new RouteDisplayInput.Point(127.1,37.1)),
            List.of(new RouteDisplayInput.Visit("206000001",1,"UP",true,new RouteDisplayInput.Point(127,37))));
    }
    private int count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
    }

    @Test
    void original_bytes_and_precise_acquisition_metadata_round_trip() {
        var receipt = success("GBIS_API", "SHAPE", "204000057", TIME);
        String id = archive.record("ROUTE_SHAPE", List.of(receipt), List.of());
        var restored = archive.restore(id);
        assertThat(restored.manifestId()).isEqualTo(id);
        assertThat(restored.purpose()).isEqualTo("ROUTE_SHAPE");
        assertThat(restored.receipts()).containsExactly(receipt);
        assertThat(restored.receipts().getFirst().body()).containsExactly(BODY);
        assertThat(restored.receipts().getFirst().acquiredAt()).isEqualTo(TIME);
        assertThat(RouteSourceReceipt.sha256(restored.document().getBytes(StandardCharsets.UTF_8))).isEqualTo(id);
    }

    @Test
    void repeated_capture_deduplicates_bytes_and_exact_receipts() {
        String first = capture();
        assertThat(capture()).isEqualTo(first);
        archive.record("ROUTE_SHAPE", List.of(success("GBIS_API", "SHAPE", "204000057", TIME.plusSeconds(1))), List.of());
        assertThat(count("route_source_blob")).isEqualTo(1);
        assertThat(count("route_source_receipt")).isEqualTo(2);
        assertThat(count("route_source_manifest")).isEqualTo(2);
    }

    @Test
    void shared_bms_capture_is_referenced_without_copying_the_body() {
        String shared = archive.record("BMS_SHARED", List.of(success("BMS", "LINKS", "SHARED", TIME)), List.of());
        var receipt = success("GBIS_API", "SHAPE", "204000057", TIME);
        String first = archive.record("ROUTE_SHAPE", List.of(receipt), List.of(shared));
        String second = archive.record("ROUTE_SHAPE", List.of(success("GBIS_API", "SHAPE", "204000058", TIME)), List.of(shared));
        assertThat(archive.restore(first).reusedManifestIds()).containsExactly(shared);
        assertThat(archive.restore(second).reusedManifestIds()).containsExactly(shared);
        assertThat(archive.restore(shared).receipts().getFirst().body()).containsExactly(BODY);
        assertThat(count("route_source_blob")).isEqualTo(1);
        assertThat(count("route_source_manifest_reuse")).isEqualTo(2);
    }

    @Test
    void directly_constructed_success_receipt_cannot_persist_credential_echoes() {
        for (String response : List.of(
                "{\"serviceKey\":\"test-credential-echo\"}",
                "{\"message\":\"https://example.invalid/api?serviceKey=test-credential-echo\"}")) {
            var unsafe = new RouteSourceReceipt("GBIS_API", "SHAPE", "204000057", Status.SUCCESS,
                200, TIME, "UNKNOWN", CoordinateSystem.UNKNOWN, response.getBytes(StandardCharsets.UTF_8));
            assertThatThrownBy(() -> archive.record("ROUTE_SHAPE",
                List.of(success("GBIS_API", "SHAPE", "204000057", TIME), unsafe), List.of()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Archive response failed retention screening")
                .hasMessageNotContaining("test-credential-echo");
            assertThat(count("route_source_blob")).isZero();
            assertThat(count("route_source_receipt")).isZero();
            assertThat(count("route_source_manifest")).isZero();
        }
    }

    @Test
    void failed_sources_preserve_status_without_response_bytes() {
        var failure = new RouteSourceReceipt("GBIS_WEB", "SHAPE", "204000057", Status.HTTP_ERROR,
            503,TIME,"UNKNOWN",CoordinateSystem.UNKNOWN,null);
        String id = archive.record("ROUTE_SHAPE", List.of(success("GBIS_API","SHAPE","204000057",TIME),failure),List.of());
        assertThat(archive.restore(id).receipts().get(1)).isEqualTo(failure);
        assertThat(archive.restore(id).receipts().get(1).body()).isNull();
        assertThat(count("route_source_blob")).isEqualTo(1);
    }

    @Test
    void binding_seals_exact_normalized_input_evidence_and_acquisition_identity() {
        String acquisition = capture();
        String id = archive.bind(routeId,versionId,acquisition,input(),RouteShapeEvidence.EMPTY);
        assertThat(archive.bind(routeId,versionId,acquisition,input(),RouteShapeEvidence.EMPTY)).isEqualTo(id);
        String document = jdbc.sql("SELECT document FROM route_input_manifest WHERE manifest_id=?")
            .param(id).query(String.class).single();
        var parsed = json.readValue(document,JdbcRouteSourceArchive.InputDocument.class);
        assertThat(parsed.input()).isEqualTo(input());
        assertThat(parsed.inputDigest()).isEqualTo(input().digest());
        assertThat(parsed.evidenceRevision()).isEqualTo(RouteShapeEvidence.EMPTY.revision());
        assertThat(parsed.acquisitionManifestId()).isEqualTo(acquisition);
        assertThat(RouteSourceReceipt.sha256(document.getBytes(StandardCharsets.UTF_8))).isEqualTo(id);
        assertThatThrownBy(() -> archive.bind(routeId,versionId+100,acquisition,input(),RouteShapeEvidence.EMPTY))
            .isInstanceOf(RuntimeException.class);
        assertThat(count("route_input_manifest")).isEqualTo(1);
    }

    @Test
    void archives_and_manifest_relations_are_immutable() {
        String id = capture();
        archive.bind(routeId,versionId,id,input(),RouteShapeEvidence.EMPTY);
        for (String table : List.of("route_source_blob","route_source_receipt","route_source_manifest",
                "route_source_manifest_receipt","route_input_manifest")) {
            assertThatThrownBy(() -> jdbc.sql("DELETE FROM " + table).update()).isInstanceOf(DataAccessException.class);
        }
        assertThatThrownBy(() -> jdbc.sql("UPDATE route_source_blob SET body=body").update())
            .isInstanceOf(DataAccessException.class);
        assertThat(archive.restore(id).receipts().getFirst().body()).containsExactly(BODY);
    }

    @Test
    void database_rejects_incorrect_byte_and_document_hashes() {
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO route_source_blob(body_sha256,body) VALUES (?,?)")
            .params("f".repeat(64),BODY).update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO route_source_manifest(manifest_id,purpose,document) VALUES (?,'TEST','{}')")
            .param("e".repeat(64)).update()).isInstanceOf(DataAccessException.class);
        assertThat(count("route_source_blob")).isZero();
        assertThat(count("route_source_manifest")).isZero();
    }

    @Test
    void dangling_reuse_is_rejected_before_a_manifest_is_published() {
        assertThatThrownBy(() -> archive.record("ROUTE_SHAPE",List.of(),List.of("f".repeat(64))))
            .isInstanceOf(RuntimeException.class);
        assertThat(count("route_source_manifest")).isZero();
        String id = capture();
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO route_source_manifest_reuse(manifest_id,ordinal,reused_manifest_id) VALUES (?,0,?)")
            .params(id,"f".repeat(64)).update()).isInstanceOf(DataAccessException.class);
    }

    @Test
    void recursive_restore_has_a_bounded_depth() {
        String id = capture();
        for (int i = 0; i < 32; i++) id = archive.record("SHARED_CHAIN",List.of(),List.of(id));
        assertThat(archive.restore(id).purpose()).isEqualTo("SHARED_CHAIN");
        String deepest = id;
        assertThatThrownBy(() -> archive.record("SHARED_CHAIN",List.of(),List.of(deepest)))
            .isInstanceOf(RuntimeException.class);
        assertThat(count("route_source_manifest")).isEqualTo(33);
    }

    @Test
    void one_record_reuses_the_verified_bms_receipt_but_later_operations_read_it_again(CapturedOutput output) {
        String shared = archive.record("BMS_SHARED", List.of(success("BMS", "LINKS", "SHARED", TIME)), List.of());
        String receiptId = receiptId(shared);
        var observed = observedArchive();
        String first = observed.transaction(() -> observed.archive().record("ROUTE_SHAPE",
            List.of(success("GBIS_API", "SHAPE", "204000057", TIME)), List.of(shared)));
        assertThat(observed.source().reads(receiptId)).isEqualTo(1);
        assertThat(observed.source().bodyBytes(receiptId)).isEqualTo(BODY.length);
        String recordLog = output.getOut().lines()
            .filter(line -> line.contains("event=route_source_archive_record") && line.contains("manifestId=" + first))
            .findFirst().orElseThrow();
        assertThat(recordLog).contains("phase=METHOD", "transactionCommitIncluded=false", "outcome=VALIDATED",
            "dbReceiptReads=2", "dbBodyBytes=" + (2 * BODY.length), "receiptCacheHits=1");

        observed.transaction(() -> observed.archive().record("ROUTE_SHAPE",
            List.of(success("GBIS_API", "SHAPE", "204000058", TIME)), List.of(shared)));
        assertThat(observed.source().reads(receiptId)).isEqualTo(2);
        observed.transaction(() -> observed.archive().restore(first));
        assertThat(observed.source().reads(receiptId)).isEqualTo(3);
        observed.transaction(() -> observed.archive().bind(routeId, versionId, first, input(), RouteShapeEvidence.EMPTY));
        assertThat(observed.source().reads(receiptId)).isEqualTo(4);
        assertThat(observed.source().bodyBytes(receiptId)).isEqualTo(4L * BODY.length);
    }

    @Test
    void cached_receipts_still_count_toward_the_final_traversal_budget_and_roll_back() {
        String shared = capture();
        String receiptId = receiptId(shared);
        String repeated = repeatedReceiptManifest(receiptId, 10000);
        var observed = observedArchive();
        assertThat(observed.transaction(() -> observed.archive().restore(repeated)).receipts()).hasSize(10000);
        assertThat(observed.source().reads(receiptId)).isEqualTo(1);
        observed.source().resetCounts();
        int manifests = count("route_source_manifest"), receipts = count("route_source_receipt");
        var unavailable = new RouteSourceReceipt("GBIS_WEB", "SHAPE", "204000057", Status.HTTP_ERROR,
            503, TIME, "UNKNOWN", CoordinateSystem.UNKNOWN, null);

        assertThatThrownBy(() -> observed.transaction(() -> observed.archive().record("OVER_RECEIPTS",
            List.of(unavailable), List.of(repeated))))
            .isInstanceOf(IllegalStateException.class).hasMessage("Archive receipt count exceeded");
        assertThat(observed.source().reads(receiptId)).isEqualTo(1);
        assertThat(count("route_source_manifest")).isEqualTo(manifests);
        assertThat(count("route_source_receipt")).isEqualTo(receipts);
    }

    @Test
    void cached_body_bytes_are_counted_and_new_bytes_are_not_double_counted() {
        byte[] megabyte = ("\"" + "a".repeat(1024 * 1024 - 2) + "\"").getBytes(StandardCharsets.UTF_8);
        var bms = new RouteSourceReceipt("BMS", "NODES", "SHARED", Status.SUCCESS, 200, TIME,
            "20261102", CoordinateSystem.WGS84_ASSUMED, megabyte);
        String shared = archive.record("BMS_SHARED", List.of(bms), List.of());
        String receiptId = receiptId(shared);
        String almostFull = repeatedReceiptManifest(receiptId, 511);
        var observed = observedArchive();
        var own = new RouteSourceReceipt("GBIS_API", "SHAPE", "204000057", Status.SUCCESS, 200, TIME,
            "20261102", CoordinateSystem.WGS84_ASSUMED, megabyte);
        String full = observed.transaction(() -> observed.archive().record("EXACT_BYTE_LIMIT", List.of(own), List.of(almostFull)));
        assertThat(observed.source().reads(receiptId)).isEqualTo(1);
        assertThat(observed.source().bodyBytes(receiptId)).isEqualTo(megabyte.length);
        assertThat(observed.transaction(() -> observed.archive().restore(full)).receipts()).containsExactly(own);
        int manifests = count("route_source_manifest");
        assertThatThrownBy(() -> observed.transaction(() -> observed.archive().record("OVER_BYTE_LIMIT", List.of(own), List.of(full))))
            .isInstanceOf(IllegalStateException.class).hasMessage("Archive exceeds byte budget");
        assertThat(count("route_source_manifest")).isEqualTo(manifests);
    }

    @Test
    void final_manifest_relations_are_rechecked_even_when_its_reused_receipt_is_cached() {
        String shared = capture();
        archive.record("EXTRA", List.of(success("GBIS_API", "SHAPE", "204000058", TIME)), List.of());
        int manifests = count("route_source_manifest");
        jdbc.sql("""
            CREATE FUNCTION test_corrupt_archive_reuse() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
                IF NEW.ordinal=0 AND (SELECT purpose FROM route_source_manifest WHERE manifest_id=NEW.manifest_id)='CHECKED_ROOT' THEN
                    INSERT INTO route_source_manifest_reuse(manifest_id,ordinal,reused_manifest_id)
                    SELECT NEW.manifest_id,1,manifest_id FROM route_source_manifest WHERE purpose='EXTRA' LIMIT 1;
                END IF;
                RETURN NEW;
            END;
            $$
            """).update();
        jdbc.sql("CREATE TRIGGER test_corrupt_archive_reuse AFTER INSERT ON route_source_manifest_reuse FOR EACH ROW EXECUTE FUNCTION test_corrupt_archive_reuse()")
            .update();
        try {
            var observed = observedArchive();
            assertThatThrownBy(() -> observed.transaction(() -> observed.archive().record("CHECKED_ROOT", List.of(), List.of(shared))))
                .isInstanceOf(IllegalStateException.class).hasMessage("Acquisition manifest reference mismatch");
            assertThat(observed.source().reads(receiptId(shared))).isEqualTo(1);
            assertThat(count("route_source_manifest")).isEqualTo(manifests);
        } finally {
            jdbc.sql("DROP TRIGGER test_corrupt_archive_reuse ON route_source_manifest_reuse").update();
            jdbc.sql("DROP FUNCTION test_corrupt_archive_reuse()").update();
        }
    }

    private String receiptId(String manifest) {
        return jdbc.sql("SELECT receipt_id FROM route_source_manifest_receipt WHERE manifest_id=? ORDER BY ordinal LIMIT 1")
            .param(manifest).query(String.class).single();
    }

    private String repeatedReceiptManifest(String receiptId, int count) {
        String document = json.writeValueAsString(new JdbcRouteSourceArchive.ManifestDocument(
            "route-source-manifest-v1", "REPEATED_RECEIPT", Collections.nCopies(count, receiptId), List.of()));
        String id = RouteSourceReceipt.sha256(document.getBytes(StandardCharsets.UTF_8));
        jdbc.sql("INSERT INTO route_source_manifest(manifest_id,purpose,document) VALUES (?,'REPEATED_RECEIPT',?)")
            .params(id, document).update();
        jdbc.sql("INSERT INTO route_source_manifest_receipt(manifest_id,ordinal,receipt_id) SELECT ?,n,? FROM generate_series(0,?) n")
            .params(id, receiptId, count - 1).update();
        return id;
    }

    private ObservedArchive observedArchive() {
        var source = new ReceiptReadCountingDataSource(dataSource);
        return new ObservedArchive(new JdbcRouteSourceArchive(JdbcClient.create(source), json), source);
    }

    private record ObservedArchive(JdbcRouteSourceArchive archive, ReceiptReadCountingDataSource source) {
        <T> T transaction(Supplier<T> action) {
            return new TransactionTemplate(new DataSourceTransactionManager(source)).execute(status -> action.get());
        }
    }

}
