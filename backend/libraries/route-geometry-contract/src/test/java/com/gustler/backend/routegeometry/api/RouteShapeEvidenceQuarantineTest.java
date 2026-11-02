package com.gustler.backend.routegeometry.api;
import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
class RouteShapeEvidenceQuarantineTest {
    @Test void positive_only_quarantine_evidence_is_usable_until_expiry_then_clears_all_node_confirmations() {
        var until=Instant.parse("2026-11-02T00:00:00Z");
        var evidence=new RouteShapeEvidence("GBIS_API",List.of(new RouteShapeEvidence.Source("GG_BMS_NODES","AVAILABLE_WITH_QUARANTINE","a".repeat(64),until)),
            List.of(new RouteShapeEvidence.TypedPoint(new RouteDisplayInput.Point(127,37),2,"UP",true)));
        assertThat(evidence.at(until.minusSeconds(1)).webPoints().getFirst().nodeConfirmed()).isTrue();
        var expired=evidence.at(until);assertThat(expired.webPoints().getFirst().nodeConfirmed()).isFalse();assertThat(expired.sources().getFirst().status()).isEqualTo("STALE");
        assertThat(expired.revision()).isNotEqualTo(evidence.revision());
    }
}
