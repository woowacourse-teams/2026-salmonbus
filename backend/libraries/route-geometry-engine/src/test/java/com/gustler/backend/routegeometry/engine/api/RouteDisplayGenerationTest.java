package com.gustler.backend.routegeometry.engine.api;

import static org.assertj.core.api.Assertions.*;
import com.gustler.backend.routegeometry.api.RouteDisplayInput;
import com.gustler.backend.routegeometry.api.RouteShapeEvidence;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RouteDisplayGenerationTest {
    @Test
    void acquisitionRefreshKeepsSemanticIdentityButLegacyCaptureTransitionInvalidatesIt() {
        String input = "1".repeat(64), evidence = "2".repeat(64), algorithm = "algorithm-config-v1";
        String legacy = RouteDisplayGeneration.key(input, evidence, algorithm);
        String captured = RouteDisplayGeneration.key(input, evidence, algorithm, "a".repeat(64));
        assertThat(RouteDisplayGeneration.key(input, evidence, algorithm, null)).isEqualTo(legacy);
        assertThat(captured).isNotEqualTo(legacy);
        assertThat(RouteDisplayGeneration.key(input, evidence, algorithm, "b".repeat(64))).isEqualTo(captured);
        assertThat(RouteDisplayGeneration.key("3".repeat(64), evidence, algorithm, "a".repeat(64))).isNotEqualTo(captured);
        assertThat(RouteDisplayGeneration.key(input, "3".repeat(64), algorithm, "a".repeat(64))).isNotEqualTo(captured);
        assertThat(RouteDisplayGeneration.key(input, evidence, "algorithm-config-v2", "a".repeat(64))).isNotEqualTo(captured);
    }

    @Test
    void ttlEvaluationPreservesAcquisitionIdentityAndStampsTheDerivedSnapshotTime() {
        var until = Instant.EPOCH.plusSeconds(100);
        var evidence = new RouteShapeEvidence("GBIS_API",
                List.of(new RouteShapeEvidence.Source("GG_BMS_NODES", "AVAILABLE", "a".repeat(64), until)),
                List.of(new RouteShapeEvidence.TypedPoint(new RouteDisplayInput.Point(127, 37), 2, "UP", true)));
        var points = List.of(new RouteGeometry.Point(127, 37), new RouteGeometry.Point(127.001, 37));
        var source = new RouteGeometry("204000057", 7, "3330", "A", "B", 1,
                List.of(new RouteGeometry.Stop("206000637", 1, "A", "UP", true, points.getFirst())),
                new RouteGeometry.Shape(UUID.randomUUID(), Instant.EPOCH, points, evidence, "b".repeat(64)));
        var before = RouteDisplayGeneration.at(source, until.minusSeconds(1));
        var expired = RouteDisplayGeneration.at(source, until);
        assertThat(before.shape().evidence().sources().getFirst().status()).isEqualTo("AVAILABLE");
        assertThat(expired.shape().evidence().sources().getFirst().status()).isEqualTo("STALE");
        assertThat(expired.shape().evidence().webPoints().getFirst().nodeConfirmed()).isFalse();
        assertThat(expired.shape().evidenceEvaluatedAt()).isEqualTo(until);
        assertThat(before.shape().evidenceEvaluatedAt()).isEqualTo(until.minusSeconds(1));
        assertThat(expired.shape().sourceManifestId()).isEqualTo(source.shape().sourceManifestId());
        assertThat(expired.shape().receivedAt()).isEqualTo(source.shape().receivedAt());
        assertThat(expired.shape().id()).isEqualTo(source.shape().id());
        assertThat(expired.points()).isEqualTo(source.points());
        assertThat(RouteDisplayGeneration.input(expired).digest()).isEqualTo(RouteDisplayGeneration.input(source).digest());
        assertThat(source.shape().evidenceEvaluatedAt()).isNull();
        assertThat(source.shape().evidence().sources().getFirst().status()).isEqualTo("AVAILABLE");
        assertThat(before.shape().evidence().revision()).isNotEqualTo(expired.shape().evidence().revision());
    }
}
