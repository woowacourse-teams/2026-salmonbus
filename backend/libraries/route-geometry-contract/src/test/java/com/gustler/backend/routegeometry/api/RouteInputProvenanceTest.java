package com.gustler.backend.routegeometry.api;

import static org.assertj.core.api.Assertions.*;
import static com.gustler.backend.routegeometry.api.RouteDisplaySamples.input;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RouteInputProvenanceTest {
    private static final String ACQUISITION = "a".repeat(64);
    private static RouteSourceReceipt receipt(RouteSourceReceipt.Status status, byte[] body) {
        return new RouteSourceReceipt("GBIS_API", "ROUTE_SHAPE", "204000057", status,
                status == RouteSourceReceipt.Status.SUCCESS ? 200 : 503, Instant.EPOCH,
                "UNSPECIFIED", RouteSourceReceipt.CoordinateSystem.WGS84_ASSUMED, body);
    }

    @Test
    void successfulReceiptPreservesExactBytesAndNeverPrintsThem() {
        byte[] supplied = "response-private-value\n\r\t".getBytes(StandardCharsets.UTF_8);
        byte[] expected = supplied.clone();
        var stored = receipt(RouteSourceReceipt.Status.SUCCESS, supplied);
        String hash = RouteSourceReceipt.sha256(expected);
        supplied[0] = 0;
        byte[] returned = stored.body();
        returned[1] = 0;
        assertThat(stored.body()).containsExactly(expected);
        assertThat(stored.bodySha256()).isEqualTo(hash);
        assertThat(stored).isEqualTo(receipt(RouteSourceReceipt.Status.SUCCESS, expected));
        assertThat(stored.hashCode()).isEqualTo(receipt(RouteSourceReceipt.Status.SUCCESS, expected).hashCode());
        assertThat(stored.toString()).contains(hash).doesNotContain("response-private-value");
    }

    @Test
    void everyFailureStatusForbidsBodyAndSuccessRequiresNonemptyBody() {
        for (var status : RouteSourceReceipt.Status.values()) {
            if (status == RouteSourceReceipt.Status.SUCCESS || status == RouteSourceReceipt.Status.DERIVED) continue;
            assertThatThrownBy(() -> receipt(status, new byte[] {1})).isInstanceOf(IllegalArgumentException.class);
            var failed = receipt(status, null);
            assertThat(failed.body()).isNull();
            assertThat(failed.bodySha256()).isNull();
        }
        var derived = new RouteSourceReceipt("GG_BMS_NODES","NORMALIZED_NODE_SNAPSHOT_V1","SHARED",
                RouteSourceReceipt.Status.DERIVED,null,Instant.EPOCH,"NOT_PROVIDED",
                RouteSourceReceipt.CoordinateSystem.WGS84_ASSUMED,"[]".getBytes(StandardCharsets.UTF_8));
        assertThat(derived.status()).isNotEqualTo(RouteSourceReceipt.Status.SUCCESS);
        assertThatThrownBy(() -> new RouteSourceReceipt(derived.sourceId(),derived.operation(),derived.scope(),
                derived.status(),200,derived.acquiredAt(),derived.providerVersion(),derived.coordinateSystem(),derived.body()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> receipt(RouteSourceReceipt.Status.SUCCESS, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> receipt(RouteSourceReceipt.Status.SUCCESS, new byte[0])).isInstanceOf(IllegalArgumentException.class);
    }

    private RouteInputProvenance provenance(String inputDigest, String evidenceRevision) {
        return new RouteInputProvenance("route-input-manifest-v1", 7, 12, ACQUISITION,
                inputDigest, evidenceRevision, input(), RouteShapeEvidence.EMPTY);
    }

    @Test
    void declaredInputAndEvidenceHashesAreRecomputed() {
        assertThatCode(() -> provenance(input().digest(), RouteShapeEvidence.EMPTY.revision())).doesNotThrowAnyException();
        assertThatThrownBy(() -> provenance("0".repeat(64), RouteShapeEvidence.EMPTY.revision()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provenance(input().digest(), "0".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void storedDocumentHashAndEveryMetadataColumnMustMatch() {
        var value = provenance(input().digest(), RouteShapeEvidence.EMPTY.revision());
        String document = "sealed-document-bytes\n";
        String hash = RouteSourceReceipt.sha256(document.getBytes(StandardCharsets.UTF_8));
        String inputHash = value.inputDigest(), evidenceHash = value.evidenceRevision();
        assertThatCode(() -> value.verifyStored(document, hash, 7, 12, inputHash, evidenceHash,
                ACQUISITION, input().routeId())).doesNotThrowAnyException();
        assertThatThrownBy(() -> value.verifyStored(document + " ", hash, 7, 12, inputHash, evidenceHash,
                ACQUISITION, input().routeId())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> value.verifyStored(document, hash, 8, 12, inputHash, evidenceHash,
                ACQUISITION, input().routeId())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> value.verifyStored(document, hash, 7, 13, inputHash, evidenceHash,
                ACQUISITION, input().routeId())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> value.verifyStored(document, hash, 7, 12, "0".repeat(64), evidenceHash,
                ACQUISITION, input().routeId())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> value.verifyStored(document, hash, 7, 12, inputHash, "0".repeat(64),
                ACQUISITION, input().routeId())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> value.verifyStored(document, hash, 7, 12, inputHash, evidenceHash,
                "b".repeat(64), input().routeId())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> value.verifyStored(document, hash, 7, 12, inputHash, evidenceHash,
                ACQUISITION, "204000081")).isInstanceOf(IllegalArgumentException.class);
    }
}
