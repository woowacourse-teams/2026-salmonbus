package com.gustler.backend.routegeometry.engine.api;

import static com.gustler.backend.routegeometry.engine.api.SourceObservationPreparation.*;
import static com.gustler.backend.routegeometry.engine.api.SourceObservationRouteMatcher.*;
import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SourceObservationPreparationTest {
    private static final MetricSpace METRES = new MetricSpace("EPSG:5179", true, 1);

    @Test
    void frozenTwentyMetreSamplingKeepsMandatoryRepeatedOccurrencesAndIntersectedPriors() {
        var prepared = prepare(METRES, List.of(new SourceVertex(10, new XY(0, 0), Role.REAL_SHAPE),
                        new SourceVertex(20, new XY(100, 0), Role.REAL_SHAPE)),
                List.of(new MandatoryVisit("same-station:visit1", 35, Set.of("ITS:A", "ITS:B")),
                        new MandatoryVisit("same-station:visit2", 35, Set.of("ITS:B", "ITS:C"))));
        assertThat(prepared.observations()).extracting(Observation::distanceProgressMetres).containsExactly(0.0, 20.0, 35.0, 40.0, 60.0, 80.0, 100.0);
        var mandatory = prepared.observations().get(2);
        assertThat(mandatory.allowedReferenceIds()).containsExactly("ITS:B");
        assertThat(mandatory.mandatoryVisitOccurrences()).containsExactly("same-station:visit1", "same-station:visit2");
        assertThat(prepared.sourceCorrespondences().get(2)).isEqualTo(new SourceAxisPosition(10, 20, .35));
        assertThat(prepared.observations().getLast().sourcePointIndex()).isEqualTo(20);
    }

    @Test
    void boundTypeTwoChangesOnlyObservationBackboneAndPreservesOriginalSourceAxis() {
        var vertices = List.of(new SourceVertex(0, new XY(0, 0), Role.REAL_SHAPE),
                new SourceVertex(1, new XY(10, 10), Role.BOUND_TYPE2_CONTROL),
                new SourceVertex(2, new XY(20, 0), Role.REAL_SHAPE));
        var prepared = prepare(METRES, vertices, List.of(new MandatoryVisit("visit", Math.sqrt(200), null)));
        assertThat(prepared.sourceAxisVertices()).isEqualTo(vertices);
        assertThat(prepared.sourceVertexProgressMetres().getLast()).isCloseTo(2 * Math.sqrt(200), within(1e-12));
        assertThat(prepared.appliedType2Indices()).containsExactly(1);
        var observation = prepared.observations().get(1);
        assertThat(observation.point()).isEqualTo(new XY(10, 0));
        assertThat(observation.sourceProgressMetres()).isCloseTo(Math.sqrt(200), within(1e-12));
        assertThat(observation.distanceProgressMetres()).isEqualTo(10);
        assertThat(observation.mandatoryVisitOccurrences()).containsExactly("visit");
        assertThat(observation.suppressedType2SourceIndices()).containsExactly(1);
        assertThat(observation.heading()).isEqualTo(new XY(1, 0));
    }

    @Test
    void unknownRolesAreNotSuppressedAndTypeTwoEndpointIsRetained() {
        var prepared = prepare(METRES, List.of(new SourceVertex(0, new XY(0, 0), Role.BOUND_TYPE2_CONTROL),
                new SourceVertex(1, new XY(10, 10), Role.UNKNOWN), new SourceVertex(2, new XY(20, 0), Role.REAL_SHAPE)),
                List.of(new MandatoryVisit("middle", Math.sqrt(200), null)));
        assertThat(prepared.providedType2Indices()).containsExactly(0);
        assertThat(prepared.appliedType2Indices()).isEmpty();
        assertThat(prepared.observations().stream().filter(row -> row.mandatoryVisitOccurrences().contains("middle")).findFirst().orElseThrow().point())
                .isEqualTo(new XY(10, 10));
    }

    @Test
    void aCollapsedMaskedBackboneRevertsToSourceWithoutErasingTheRealLoop() {
        var vertices = List.of(new SourceVertex(0, new XY(0, 0), Role.REAL_SHAPE),
                new SourceVertex(1, new XY(10, 0), Role.BOUND_TYPE2_CONTROL), new SourceVertex(2, new XY(0, 0), Role.REAL_SHAPE));
        var prepared = prepare(METRES, vertices, List.of());
        assertThat(prepared.sourceAxisVertices()).isEqualTo(vertices);
        assertThat(prepared.appliedType2Indices()).isEmpty();
        assertThat(prepared.notes()).contains("TYPE2_BACKBONE_COLLAPSE_REVERTED_TO_FULL_SOURCE_AXIS");
        assertThat(prepared.observations().getLast().sourceProgressMetres()).isEqualTo(20);
    }

    @Test
    void invalidMandatorySourcePositionsAndDuplicateVisitIdsAreRejected() {
        var source = List.of(new SourceVertex(0, new XY(0, 0), Role.REAL_SHAPE), new SourceVertex(1, new XY(10, 0), Role.REAL_SHAPE));
        assertThatThrownBy(() -> prepare(METRES, source, List.of(new MandatoryVisit("visit", 11, null))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("OUTSIDE_SOURCE_AXIS");
        assertThatThrownBy(() -> prepare(METRES, source, List.of(new MandatoryVisit("same", 1, null), new MandatoryVisit("same", 2, null))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("DUPLICATE_VISIT_OCCURRENCE");
    }
}
