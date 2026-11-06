package com.gustler.backend.routegeometry.engine.api;

import static com.gustler.backend.routegeometry.engine.api.SourceObservationRouteMatcher.*;
import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SourceObservationRouteMatcherTest {
    private static final MetricSpace METRES = new MetricSpace("EPSG:5179", true, 1);
    private static XY xy(double x, double y) { return new XY(x, y); }
    private static DirectedEdge edge(int index, String id, String from, String to, XY... points) {
        return new DirectedEdge(index, "ITS:" + id, from, to, "its:fixture", Source.ITS, List.of(points),
                Map.of("ROAD_USE", "0", "REST_VEH", "0", "REST_H", "0", "REST_W", "0"));
    }
    private static DirectedEdge attrs(DirectedEdge edge, String field, Object value) {
        Map<String, Object> attributes = new HashMap<>(edge.attributes()); attributes.put(field, value);
        return new DirectedEdge(edge.originalIndex(), edge.id(), edge.from(), edge.to(), edge.namespace(), edge.source(), edge.points(), attributes);
    }
    private static SourceObservationRouteMatcher matcher(List<DirectedEdge> edges, TurnFact... facts) {
        return new SourceObservationRouteMatcher(new Graph(METRES, edges, List.of(facts)));
    }
    private static Observation observation(int index, double progress, XY point, XY heading, String... allowed) {
        return new Observation(index, progress, progress, point, heading, allowed.length == 0 ? null : Set.of(allowed), List.of(), List.of());
    }
    private static Candidate candidate(DirectedEdge edge, double position) {
        return new Candidate(edge.originalIndex(), edge.id(), position, edge.points().getFirst(), 0, 0.0, 0, List.of());
    }
    private static TurnFact fact(DirectedEdge a, DirectedEdge b, String type, String operation, String binding) {
        return new TurnFact(17, a.id(), b.id(), a.to(), type, operation, binding,
                List.of(new LinkWitness(a.id(), a.from(), a.to())), List.of(new LinkWitness(b.id(), b.from(), b.to())));
    }

    @Test
    void straightSourceSelectsOriginalIndexedReferenceWithoutGrantingAccuracy() {
        var edge = edge(94, "A", "S", "T", xy(0, 0), xy(100, 0));
        var result = matcher(List.of(edge)).matchPrepared(List.of(
                observation(42, 0, xy(10, 0), xy(1, 0)), observation(43, 20, xy(30, 0), xy(1, 0))));
        assertThat(result.selectedScore()).isEqualTo(0);
        assertThat(result.selections()).allSatisfy(row -> assertThat(row.candidate().originalEdgeIndex()).isEqualTo(94));
        assertThat(result.intervals().getFirst().kind()).isEqualTo(IntervalKind.REFERENCE_CANDIDATE);
        assertThat(result.intervals().getFirst().referenceOccurrences()).containsExactly(new EdgeOccurrence(94, "ITS:A", 10, 30));
        assertThat(result.routeLegalityVerified()).isFalse(); assertThat(result.geometryAccuracyVerified()).isFalse();
        assertThat(result.intervals().getFirst().reasonCodes()).contains("EDGE_INTERIOR_NOT_ROUTE_APPROVAL");
    }

    @Test
    void globalOptimumRejectsLocallyNearestDisconnectedRoad() {
        var nearest = edge(0, "nearest", "A0", "A1", xy(0, 0), xy(100, 0));
        var connected = edge(1, "global", "B0", "B1", xy(0, 2), xy(100, 2));
        var matcher = matcher(List.of(nearest, connected));
        var first = observation(0, 0, xy(10, 0), xy(1, 0));
        assertThat(matcher.candidates(first).getFirst().edgeId()).isEqualTo(nearest.id());
        var result = matcher.matchPrepared(List.of(first,
                observation(1, 10, xy(20, 0), xy(1, 0)),
                observation(2, 20, xy(30, 0), xy(1, 0), connected.id())));
        assertThat(result.selections()).allSatisfy(row -> assertThat(row.candidate().edgeId()).isEqualTo(connected.id()));
        assertThat(result.selectedScore()).isCloseTo(3 * Math.pow(2.0 / 8, 2), within(1e-12));
    }

    @Test
    void confirmedUnusedAndBusRestrictedCandidatesAreExcludedBeforeFixedTopFour() {
        List<DirectedEdge> edges = new ArrayList<>();
        for (int i = 0; i < 5; i++) edges.add(attrs(edge(i, "blocked" + i, "S" + i, "T" + i, xy(0, i), xy(100, i)), "ROAD_USE", "1"));
        for (int i = 5; i < 10; i++) edges.add(edge(i, "available" + i, "S" + i, "T" + i, xy(0, i), xy(100, i)));
        var candidates = matcher(edges).candidates(observation(0, 0, xy(10, 0), xy(1, 0)));
        assertThat(candidates).extracting(Candidate::originalEdgeIndex).containsExactly(5, 6, 7, 8);
        assertThat(matcher(List.of(attrs(edges.get(5), "REST_VEH", "3"))).candidates(observation(0, 0, xy(10, 5), xy(1, 0)))).isEmpty();
    }

    @Test
    void incomingEdgeStateCanRevisitNodeToAvoidConfirmedTurnRatherThanTeleportOrGloballyBanLoops() {
        var a = edge(0, "A", "S", "N", xy(0, 0), xy(10, 0));
        var b = edge(1, "B", "N", "T", xy(10, 0), xy(20, 0));
        var c = edge(2, "C", "N", "P", xy(10, 0), xy(10, 10));
        var d = edge(3, "D", "P", "N", xy(10, 10), xy(10, 0));
        var connection = matcher(List.of(a, b, c, d), fact(a, b, "003", "0", "DIRECTED_INCIDENT"))
                .transition(candidate(a, 5), candidate(b, 5), 30);
        assertThat(connection.found()).isTrue(); assertThat(connection.cost()).isZero();
        assertThat(connection.occurrences()).extracting(EdgeOccurrence::edgeId).containsExactly(a.id(), c.id(), d.id(), b.id());
        assertThat(connection.hardExclusions()).anySatisfy(row -> assertThat(row.reasonCodes()).contains("EXPLICIT_ALL_DAY_DIRECTED_PROHIBITION"));
        assertThat(connection.reasonCodes()).contains("NO_EXPLICIT_TURN_RULE");
    }

    @Test
    void confirmedTurnWithoutAlternativeIsNoPathAndGlobalOutputFallsBackToSource() {
        var a = edge(0, "A", "S", "N", xy(0, 0), xy(10, 0));
        var b = edge(1, "B", "N", "T", xy(10, 0), xy(20, 0));
        var matcher = matcher(List.of(a, b), fact(a, b, "003", "0", "DIRECTED_INCIDENT"));
        var connection = matcher.transition(candidate(a, 5), candidate(b, 5), 10);
        assertThat(connection.found()).isFalse(); assertThat(connection.occurrences()).isEmpty();
        assertThat(connection.hardExclusions()).isNotEmpty();
        var result = matcher.matchPrepared(List.of(observation(0, 0, xy(5, 0), xy(1, 0), a.id()), observation(1, 10, xy(15, 0), xy(1, 0), b.id())));
        assertThat(result.intervals().getFirst().kind()).isEqualTo(IntervalKind.SOURCE_FALLBACK);
        assertThat(result.intervals().getFirst().referenceOccurrences()).isEmpty();
        assertThat(result.intervals().getFirst().reasonCodes()).contains("ALL_REFERENCE_TRANSITIONS_UNAVAILABLE", "NO_PATH_WITH_KNOWN_CONSTRAINTS", "EXPLICIT_ALL_DAY_DIRECTED_PROHIBITION");
    }

    @Test
    void conflictingBusExceptionAndConditionalTurnRemainUnknownRatherThanBeingPromotedOrHardBanned() {
        var a = edge(0, "A", "S", "N", xy(0, 0), xy(10, 0));
        var b = edge(1, "B", "N", "T", xy(10, 0), xy(20, 0));
        var compound = matcher(List.of(a, b), fact(a, b, "003", "0", "DIRECTED_INCIDENT"), fact(a, b, "002", "0", "DIRECTED_INCIDENT"))
                .transition(candidate(a, 5), candidate(b, 5), 10);
        assertThat(compound.found()).isTrue(); assertThat(compound.reasonCodes()).contains("CONFLICTING_OR_COMPOUND_MOVEMENT_CODES");
        var conditional = matcher(List.of(a, b), fact(a, b, "003", "1", "DIRECTED_INCIDENT"))
                .transition(candidate(a, 5), candidate(b, 5), 10);
        assertThat(conditional.found()).isTrue(); assertThat(conditional.reasonCodes()).contains("TIME_APPLICABILITY_UNRESOLVED");
        var bus = matcher(List.of(a, b), fact(a, b, "002", "0", "DIRECTED_INCIDENT"))
                .transition(candidate(a, 5), candidate(b, 5), 10);
        assertThat(bus.reasonCodes()).contains("BUS_EXCEPTION_CONTEXT_UNRESOLVED");
    }

    @Test
    void unboundViaAndVehicleDimensionsRemainInSelectedUncertainty() {
        var a = attrs(edge(0, "A", "S", "N", xy(0, 0), xy(10, 0)), "REST_H", "350");
        var b = edge(1, "B", "N", "T", xy(10, 0), xy(20, 0));
        var matcher = matcher(List.of(a, b), fact(a, b, "003", "0", "NONINCIDENT"));
        var candidate = matcher.candidates(observation(0, 0, xy(5, 0), xy(1, 0), a.id())).getFirst();
        assertThat(candidate.uncertaintyReasons()).contains("VERIFIED_VEHICLE_DIMENSIONS_REQUIRED:REST_H", "NONINCIDENT_VIA_CONTEXT_UNRESOLVED");
        var connection = matcher.transition(candidate, candidate(b, 5), 10);
        assertThat(connection.found()).isTrue(); assertThat(connection.reasonCodes()).contains("TURN_REFERENCE_BINDING_UNRESOLVED");
    }

    @Test
    void sameXYOrSameNodeSpellingDoesNotCreateSourceOrNamespaceBridge() {
        var a = edge(0, "A", "S", "N", xy(0, 0), xy(10, 0));
        var b = new DirectedEdge(1, "native:B", "N", "T", "ngii:other", Source.NGII, List.of(xy(10, 0), xy(20, 0)), Map.of());
        var connection = matcher(List.of(a, b)).transition(candidate(a, 5), candidate(b, 5), 10);
        assertThat(connection.found()).isFalse();
        assertThat(connection.reasonCodes()).contains("CROSS_SOURCE_OR_NAMESPACE_TRANSITION_UNSUPPORTED");
    }

    @Test
    void invalidAndCoordinateConflictingPortsAreIsolatedWithoutMutatingDeclaredEdges() {
        var a = edge(0, "A", "S", "N", xy(0, 0), xy(10, 0));
        var b = edge(1, "B", "N", "T", xy(500, 0), xy(510, 0));
        var matcher = matcher(List.of(a, b));
        assertThat(matcher.transition(candidate(a, 5), candidate(b, 5), 10).found()).isFalse();
        assertThat(a.to()).isEqualTo("N"); assertThat(b.from()).isEqualTo("N");
        assertThat(matcher.candidates(observation(0, 0, xy(5, 0), xy(1, 0))).getFirst().uncertaintyReasons()).contains("UNRESOLVED_SOURCE_PORT_ISOLATED");
        for (String invalid : List.of("", "0", "None", "-", "0000000000")) {
            var left = edge(0, "A", "S", invalid, xy(0, 0), xy(10, 0));
            var right = edge(1, "B", invalid, "T", xy(10, 0), xy(20, 0));
            assertThat(matcher(List.of(left, right)).transition(candidate(left, 5), candidate(right, 5), 10).found()).isFalse();
        }
    }

    @Test
    void repeatedVisitsAndLinkOccurrencesSurviveTheGlobalLoop() {
        var a = edge(0, "A", "S", "N", xy(0, 0), xy(10, 0));
        var b = edge(1, "B", "N", "P", xy(10, 0), xy(10, 10));
        var c = edge(2, "C", "P", "Q", xy(10, 10), xy(0, 10));
        var d = edge(3, "D", "Q", "S", xy(0, 10), xy(0, 0));
        var first = new Observation(1, 0, 0, xy(5, 0), xy(1, 0), Set.of(a.id()), List.of("station:visit1"), List.of());
        var last = new Observation(9, 40, 40, xy(5, 0), xy(1, 0), Set.of(a.id()), List.of("station:visit2"), List.of());
        var result = matcher(List.of(a, b, c, d)).matchPrepared(List.of(first, observation(5, 20, xy(5, 10), xy(-1, 0), c.id()), last));
        assertThat(result.selections()).extracting(row -> row.candidate().edgeId()).containsExactly(a.id(), c.id(), a.id());
        assertThat(result.selections().getFirst().observation().mandatoryVisitOccurrences()).containsExactly("station:visit1");
        assertThat(result.selections().getLast().observation().mandatoryVisitOccurrences()).containsExactly("station:visit2");
        assertThat(result.intervals()).allSatisfy(interval -> assertThat(interval.kind()).isEqualTo(IntervalKind.REFERENCE_CANDIDATE));
    }

    @Test
    void equivalentCandidatesAtSharedEndpointDoNotCreateFalseZeroMargin() {
        var a = edge(0, "A", "S", "N", xy(0, 0), xy(10, 0));
        var b = edge(1, "B", "N", "T", xy(10, 0), xy(20, 0));
        var result = matcher(List.of(a, b)).matchPrepared(List.of(observation(0, 0, xy(10, 0), xy(1, 0)), observation(1, 5, xy(15, 0), xy(1, 0))));
        assertThat(result.selections().getFirst().candidate().edgeId()).isEqualTo(a.id());
        assertThat(result.selections().getFirst().confidenceMargin()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void equallyPlausibleDisconnectedPathsRetainAmbiguityAndSourceFallback() {
        var a = edge(10, "A", "S", "T", xy(0, -2), xy(100, -2));
        var b = edge(20, "B", "P", "Q", xy(0, 2), xy(100, 2));
        var result = matcher(List.of(b, a)).matchPrepared(List.of(observation(0, 0, xy(10, 0), xy(1, 0)), observation(1, 10, xy(20, 0), xy(1, 0))));
        assertThat(result.candidateLayers().getFirst()).extracting(Candidate::originalEdgeIndex).containsExactly(10, 20);
        assertThat(result.selections().getFirst().confidenceMargin()).isZero();
        assertThat(result.intervals().getFirst().kind()).isEqualTo(IntervalKind.SOURCE_FALLBACK);
        assertThat(result.intervals().getFirst().reasonCodes()).contains("BELOW_EXISTING_CONFIDENCE_MARGIN");
    }

    @Test
    void fixedStateBudgetTerminatesLongSearchAndDoesNotInventAConnector() {
        List<DirectedEdge> edges = new ArrayList<>();
        for (int i = 0; i < 3003; i++) edges.add(edge(i, "e" + i, "n" + i, "n" + (i + 1), xy(i * .5, 0), xy((i + 1) * .5, 0)));
        var connection = matcher(edges).transition(candidate(edges.getFirst(), .25), candidate(edges.getLast(), .25), 1501);
        assertThat(connection.found()).isFalse(); assertThat(connection.reasonCodes()).contains("SEARCH_STATE_LIMIT");
        assertThat(connection.searchedStates()).isEqualTo(3001); assertThat(connection.occurrences()).isEmpty();
    }

    @Test
    void millimetreSameEdgeToleranceCannotPublishAReverseFragment() {
        var edge = edge(0, "A", "S", "T", xy(0, 0), xy(100, 0));
        var connection = matcher(List.of(edge)).transition(candidate(edge, 10), candidate(edge, 9.9995), 1);
        assertThat(connection.found()).isTrue(); assertThat(connection.cost()).isEqualTo(.125);
        assertThat(connection.occurrences()).isEmpty();
        assertThat(connection.reasonCodes()).contains("SAME_EDGE_TOLERANCE_HAS_NO_FORWARD_FRAGMENT");
    }

    @Test
    void missingRadiusCoverageIsSourceFallbackAndEmptyPriorIsNotUnconstrained() {
        var edge = edge(0, "A", "S", "T", xy(0, 0), xy(100, 0));
        var matcher = matcher(List.of(edge));
        var missing = matcher.matchPrepared(List.of(observation(0, 0, xy(10, 100), xy(1, 0)), observation(1, 10, xy(20, 100), xy(1, 0))));
        assertThat(missing.selections()).allSatisfy(row -> assertThat(row.candidate()).isNull());
        assertThat(missing.intervals().getFirst().reasonCodes()).contains("NO_ELIGIBLE_CANDIDATE_IN_FROZEN_RADIUS");
        var constrained = new Observation(0, 0, 0, xy(10, 0), xy(1, 0), Set.of(), List.of(), List.of());
        assertThat(matcher.candidates(constrained)).isEmpty();
    }

    @Test
    void inputRecordsAreDefensivelyImmutableAndInvalidAxesAreRejected() {
        List<XY> points = new ArrayList<>(List.of(xy(0, 0), xy(100, 0)));
        List<String> nested = new ArrayList<>(List.of("original")); Map<String, Object> attributes = new HashMap<>(); attributes.put("raw", nested);
        var edge = new DirectedEdge(7, "native:a", "S", "T", "native:epoch", Source.NGII, points, attributes);
        points.clear(); nested.clear(); attributes.clear();
        assertThat(edge.points()).hasSize(2); assertThat((List<?>) edge.attributes().get("raw")).hasSize(1);
        assertThatThrownBy(() -> edge.attributes().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new MetricSpace("EPSG:4326", false, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> matcher(List.of(edge)).matchPrepared(List.of(observation(0, 0, xy(0, 0), xy(1, 0)), observation(1, 0, xy(10, 0), xy(1, 0)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("STRICTLY_INCREASING");
    }

    @Test
    void mutableRawNumberCannotMutateTheDeclaredImmutableGraphAfterConstruction() {
        assertThatThrownBy(() -> new DirectedEdge(0, "native:a", "S", "T", "native:epoch", Source.NGII,
                List.of(xy(0, 0), xy(10, 0)), Map.of("raw", new java.util.concurrent.atomic.AtomicInteger(1))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("NOT_IMMUTABLE_JSON");
    }

    @Test
    void sharedEndpointOrSubMillimetreFragmentDoesNotTurnAStationarySelectionIntoReferenceGeometry() {
        var a = edge(0, "A", "S", "N", xy(0, -10), xy(0, 0));
        var b = edge(1, "B", "N", "T", xy(0, 0), xy(0, 10));
        var matcher = matcher(List.of(a, b));
        var result = matcher.matchPrepared(List.of(observation(0, 0, xy(.1, 0), xy(1, 0), a.id()),
                observation(1, .1, xy(.2, 0), xy(1, 0), b.id())));
        assertThat(result.selections()).extracting(row -> row.candidate().edgeId()).containsExactly(a.id(), b.id());
        assertThat(result.selectedScore()).isCloseTo(6.49328125, within(1e-12));
        assertThat(matcher.transition(candidate(a, 10), candidate(b, 0), .1).found()).isTrue();
        assertThat(result.intervals().getFirst().kind()).isEqualTo(IntervalKind.SOURCE_FALLBACK);
        assertThat(result.intervals().getFirst().referenceOccurrences()).isEmpty();
        assertThat(result.intervals().getFirst().reasonCodes()).contains("REFERENCE_FRAGMENT_COLLAPSED_AFTER_EXISTING_ONE_MILLIMETRE_CLEAN");

        var straight = edge(0, "straight", "S", "T", xy(0, 0), xy(10, 0));
        var tiny = matcher(List.of(straight)).matchPrepared(List.of(observation(0, 0, xy(1, 0), xy(1, 0)),
                observation(0, .0005, xy(1.0005, 0), xy(1, 0))));
        assertThat(tiny.selections()).allSatisfy(row -> assertThat(row.candidate()).isNotNull());
        assertThat(tiny.intervals().getFirst().kind()).isEqualTo(IntervalKind.SOURCE_FALLBACK);
        assertThat(tiny.intervals().getFirst().referenceOccurrences()).isEmpty();
    }
}
