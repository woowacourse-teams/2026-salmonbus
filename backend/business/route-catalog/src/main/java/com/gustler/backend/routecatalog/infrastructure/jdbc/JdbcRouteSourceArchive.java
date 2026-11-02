package com.gustler.backend.routecatalog.infrastructure.jdbc;

import com.gustler.backend.gbis.api.GbisCapturePolicy;
import com.gustler.backend.gbis.api.GbisResponseCapture;
import com.gustler.backend.routecatalog.domain.geometry.RouteSourceArchive;
import com.gustler.backend.routegeometry.api.RouteDisplayInput;
import com.gustler.backend.routegeometry.api.RouteShapeEvidence;
import com.gustler.backend.routegeometry.api.RouteSourceReceipt;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Exact accepted bytes and credential-free metadata, sealed independently from serving state. */
@Repository
public class JdbcRouteSourceArchive implements RouteSourceArchive {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_MANIFESTS = 1024;
    private static final int MAX_RECEIPTS = 10000;
    private static final long MAX_BYTES = 512L * 1024 * 1024;

    public JdbcRouteSourceArchive(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record ReceiptDocument(String format, String sourceId, String operation, String scope,
        RouteSourceReceipt.Status status, Integer httpStatus, Instant acquiredAt, String providerVersion,
        RouteSourceReceipt.CoordinateSystem coordinateSystem, String bodySha256) {}
    public record ManifestDocument(String format, String purpose, List<String> receiptIds,
        List<String> reusedManifestIds) {}
    public record InputDocument(String format, long routeId, long referenceVersionId,
        String acquisitionManifestId, String inputDigest, String evidenceRevision,
        RouteDisplayInput input, RouteShapeEvidence evidence) {}
    private record ReceiptRow(String document, String bodySha256, byte[] body) {}
    private record ManifestRow(String purpose, String document) {}

    @Override
    @Transactional
    public String record(String purpose, List<RouteSourceReceipt> receipts, List<String> reusedManifestIds) {
        require(purpose != null && purpose.matches("[A-Z0-9_]{1,64}"), "Invalid archive purpose");
        receipts = List.copyOf(receipts);
        reusedManifestIds = List.copyOf(reusedManifestIds);
        require(receipts.size() <= MAX_RECEIPTS && reusedManifestIds.size() <= MAX_MANIFESTS,
            "Archive exceeds bounded receipt count");
        var verification = new Verification();
        for (String id : reusedManifestIds) restore(id, verification, 0);
        List<String> receiptIds = new ArrayList<>();
        for (var receipt : receipts) {
            String bodySha = receipt.bodySha256();
            if (bodySha != null) {
                byte[] body = receipt.body();
                require(GbisCapturePolicy.screen(body, null, json).disposition()
                    == GbisResponseCapture.Disposition.AVAILABLE, "Archive response failed retention screening");
                verification.bytes += body.length;
                require(verification.bytes <= MAX_BYTES, "Archive exceeds byte budget");
                jdbc.sql("INSERT INTO route_source_blob(body_sha256,body) VALUES (?,?) ON CONFLICT DO NOTHING")
                    .params(bodySha, body).update();
            }
            String document = json.writeValueAsString(new ReceiptDocument("route-source-receipt-v1",
                receipt.sourceId(), receipt.operation(), receipt.scope(), receipt.status(), receipt.httpStatus(),
                receipt.acquiredAt(), receipt.providerVersion(), receipt.coordinateSystem(), bodySha));
            String id = hash(document);
            jdbc.sql("INSERT INTO route_source_receipt(receipt_id,document,body_sha256) VALUES (?,?,?) ON CONFLICT DO NOTHING")
                .param(id).param(document).param(3, bodySha, java.sql.Types.VARCHAR).update();
            receiptIds.add(id);
        }
        String document = json.writeValueAsString(new ManifestDocument("route-source-manifest-v1", purpose,
            List.copyOf(receiptIds), reusedManifestIds));
        String id = hash(document);
        jdbc.sql("INSERT INTO route_source_manifest(manifest_id,purpose,document) VALUES (?,?,?) ON CONFLICT DO NOTHING")
            .params(id, purpose, document).update();
        for (int i = 0; i < receiptIds.size(); i++)
            jdbc.sql("INSERT INTO route_source_manifest_receipt(manifest_id,ordinal,receipt_id) VALUES (?,?,?) ON CONFLICT DO NOTHING")
                .params(id, i, receiptIds.get(i)).update();
        for (int i = 0; i < reusedManifestIds.size(); i++)
            jdbc.sql("INSERT INTO route_source_manifest_reuse(manifest_id,ordinal,reused_manifest_id) VALUES (?,?,?) ON CONFLICT DO NOTHING")
                .params(id, i, reusedManifestIds.get(i)).update();
        // Verify relational references against the exact sealed document before returning its identity.
        restore(id, new Verification(), 0);
        return id;
    }

    @Override
    @Transactional
    public String bind(long routeId, long referenceVersionId, String acquisitionManifestId,
                       RouteDisplayInput input, RouteShapeEvidence evidence) {
        restore(acquisitionManifestId, new Verification(), 0);
        String publicId = jdbc.sql("""
            SELECT r.public_route_id FROM route r JOIN route_version v ON v.route_id=r.id
            WHERE r.id=? AND v.id=? AND r.source_id='GBIS'
            """).params(routeId, referenceVersionId).query(String.class).optional()
            .orElseThrow(() -> invalid("Archive route version does not exist"));
        require(publicId.equals(input.routeId()), "Archive input route mismatch");
        String document = json.writeValueAsString(new InputDocument("route-input-manifest-v1", routeId,
            referenceVersionId, acquisitionManifestId, input.digest(), evidence.revision(), input, evidence));
        String id = hash(document);
        jdbc.sql("""
            INSERT INTO route_input_manifest(manifest_id,route_id,reference_version_id,input_digest,
                evidence_revision,acquisition_manifest_id,document) VALUES (?,?,?,?,?,?,?) ON CONFLICT DO NOTHING
            """).params(id, routeId, referenceVersionId, input.digest(), evidence.revision(),
                acquisitionManifestId, document).update();
        return id;
    }

    @Override
    @Transactional(readOnly = true)
    public ArchivedManifest restore(String manifestId) {
        return restore(manifestId, new Verification(), 0);
    }

    private static final class Verification {
        final Set<String> active = new HashSet<>();
        final Map<String, ArchivedManifest> done = new HashMap<>();
        int receipts;
        long bytes;
    }

    private ArchivedManifest restore(String id, Verification verification, int depth) {
        require(id != null && id.matches("[0-9a-f]{64}"), "Invalid manifest identity");
        require(depth <= MAX_DEPTH, "Archive reference depth exceeded");
        require(!verification.active.contains(id), "Archive reference cycle");
        var already = verification.done.get(id);
        if (already != null) return already;
        require(verification.done.size() + verification.active.size() < MAX_MANIFESTS,
            "Archive manifest count exceeded");
        verification.active.add(id);
        ManifestRow row = jdbc.sql("SELECT purpose,document FROM route_source_manifest WHERE manifest_id=?")
            .param(id).query((r, n) -> new ManifestRow(r.getString(1), r.getString(2))).optional()
            .orElseThrow(() -> invalid("Missing acquisition manifest"));
        require(hash(row.document()).equals(id), "Acquisition manifest hash mismatch");
        ManifestDocument manifest = json.readValue(row.document(), ManifestDocument.class);
        require("route-source-manifest-v1".equals(manifest.format())
            && row.purpose().equals(manifest.purpose()), "Acquisition manifest metadata mismatch");
        require(manifest.receiptIds() != null && manifest.reusedManifestIds() != null,
            "Acquisition manifest references missing");
        List<String> receiptIds = jdbc.sql("SELECT receipt_id FROM route_source_manifest_receipt WHERE manifest_id=? ORDER BY ordinal")
            .param(id).query(String.class).list();
        List<String> reusedIds = jdbc.sql("SELECT reused_manifest_id FROM route_source_manifest_reuse WHERE manifest_id=? ORDER BY ordinal")
            .param(id).query(String.class).list();
        require(receiptIds.equals(manifest.receiptIds()) && reusedIds.equals(manifest.reusedManifestIds()),
            "Acquisition manifest reference mismatch");
        List<RouteSourceReceipt> receipts = new ArrayList<>();
        for (String receiptId : receiptIds) {
            require(++verification.receipts <= MAX_RECEIPTS, "Archive receipt count exceeded");
            ReceiptRow receipt = jdbc.sql("""
                SELECT r.document,r.body_sha256,b.body FROM route_source_receipt r
                LEFT JOIN route_source_blob b ON b.body_sha256=r.body_sha256 WHERE r.receipt_id=?
                """).param(receiptId).query((r, n) -> new ReceiptRow(r.getString(1),r.getString(2),r.getBytes(3)))
                .optional().orElseThrow(() -> invalid("Missing archive receipt"));
            require(hash(receipt.document()).equals(receiptId), "Archive receipt hash mismatch");
            ReceiptDocument metadata = json.readValue(receipt.document(), ReceiptDocument.class);
            require("route-source-receipt-v1".equals(metadata.format())
                && java.util.Objects.equals(receipt.bodySha256(), metadata.bodySha256()), "Receipt metadata mismatch");
            if (receipt.bodySha256() != null) {
                require(receipt.body() != null && RouteSourceReceipt.sha256(receipt.body()).equals(receipt.bodySha256()),
                    "Archive response bytes missing or changed");
                verification.bytes += receipt.body().length;
                require(verification.bytes <= MAX_BYTES, "Archive exceeds byte budget");
            }
            receipts.add(new RouteSourceReceipt(metadata.sourceId(),metadata.operation(),metadata.scope(),metadata.status(),
                metadata.httpStatus(),metadata.acquiredAt(),metadata.providerVersion(),metadata.coordinateSystem(),receipt.body()));
        }
        for (String reusedId : reusedIds) restore(reusedId, verification, depth + 1);
        var result = new ArchivedManifest(id, manifest.purpose(), row.document(), List.copyOf(receipts), List.copyOf(reusedIds));
        verification.active.remove(id);
        verification.done.put(id, result);
        return result;
    }

    private static String hash(String value) {
        return RouteSourceReceipt.sha256(value.getBytes(StandardCharsets.UTF_8));
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw invalid(message);
    }
    private static IllegalStateException invalid(String message) {
        return new IllegalStateException(message);
    }
}
