package com.gustler.backend.routegeometry.engine.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Global candidate selection port of frozen v5 Matcher + E2 HARD, in one projected
 * metre space. It returns source-bound reference occurrences and explicit gaps;
 * it does not construct display curves, assign accuracy grades, or publish data.
 * Prepared observations retain separate source and observation-backbone axes.
 */
public final class SourceObservationRouteMatcher {
    public static final String REVISION = "source-observation-global-v2";
    public static final int CANDIDATE_K = 4;
    public static final int MAX_SEARCH_STATES = 3000;
    public static final double CANDIDATE_RADIUS_METRES = 45;
    public static final double EMISSION_SIGMA_METRES = 8;
    public static final double NULL_EMISSION_COST = 10;
    public static final double NULL_TRANSITION_COST = 3;
    public static final double MINIMUM_REFERENCE_MARGIN = 2;
    private static final double EPS = 1e-7;

    public enum Source { ITS, NGII }
    public enum Status { UNKNOWN, PROHIBITED }
    public enum IntervalKind { REFERENCE_CANDIDATE, SOURCE_FALLBACK }

    public record XY(double x, double y) {
        public XY { finite(x); finite(y); }
    }

    public record MetricSpace(String coordinateSystem, boolean projected, double xyUnitToMetres) {
        public MetricSpace {
            named(coordinateSystem);
            if (!projected || xyUnitToMetres != 1) throw invalid("METRIC_PROJECTED_CRS_REQUIRED");
        }
    }

    public record DirectedEdge(int originalIndex, String id, String from, String to,
                               String namespace, Source source, List<XY> points,
                               Map<String, Object> attributes) {
        public DirectedEdge {
            if (originalIndex < 0) throw invalid("NONNEGATIVE_ORIGINAL_EDGE_INDEX_REQUIRED");
            named(id); named(namespace); Objects.requireNonNull(source);
            points = List.copyOf(points);
            if (points.size() < 2) throw invalid("EDGE_POLYLINE_REQUIRED");
            attributes = immutableAttributes(attributes);
        }
    }

    /** Source-record witnesses, not nearest-node inferred adjacency. */
    public record LinkWitness(String edgeId, String from, String to) {
        public LinkWitness { named(edgeId); }
    }

    public record TurnFact(long sourceRecordIndex, String incomingEdgeId, String outgoingEdgeId,
                           String viaNode, String turnType, String operation,
                           String bindingStatus, List<LinkWitness> startLinks,
                           List<LinkWitness> endLinks) {
        public TurnFact {
            if (sourceRecordIndex < 0) throw invalid("TURN_SOURCE_RECORD_INDEX_REQUIRED");
            named(incomingEdgeId); named(outgoingEdgeId); named(viaNode); named(bindingStatus);
            startLinks = List.copyOf(startLinks); endLinks = List.copyOf(endLinks);
        }
    }

    public record Graph(MetricSpace space, List<DirectedEdge> edges, List<TurnFact> turnFacts) {
        public Graph {
            Objects.requireNonNull(space); edges = List.copyOf(edges); turnFacts = List.copyOf(turnFacts);
        }
    }

    /**
     * Source index/progress is metadata correspondence, not a nearest-point fit.
     * TYPE2 suppression has already been applied when preparing the backbone;
     * suppressed original indices remain explicit rather than becoming fake GPS.
     * null priors means unconstrained, an empty set means no allowed references.
     */
    public record Observation(int sourcePointIndex, double sourceProgressMetres,
                              double distanceProgressMetres, XY point, XY heading,
                              Set<String> allowedReferenceIds, List<String> mandatoryVisitOccurrences,
                              List<Integer> suppressedType2SourceIndices) {
        public Observation {
            if (sourcePointIndex < 0) throw invalid("SOURCE_POINT_INDEX_REQUIRED");
            nonnegative(sourceProgressMetres); nonnegative(distanceProgressMetres);
            Objects.requireNonNull(point); Objects.requireNonNull(heading);
            allowedReferenceIds = allowedReferenceIds == null ? null : Set.copyOf(allowedReferenceIds);
            mandatoryVisitOccurrences = List.copyOf(mandatoryVisitOccurrences);
            suppressedType2SourceIndices = List.copyOf(suppressedType2SourceIndices);
        }
    }

    public record Candidate(int originalEdgeIndex, String edgeId, double positionMetres,
                            XY point, double distanceMetres, Double headingErrorDegrees,
                            double emissionCost, List<String> uncertaintyReasons) {
        public Candidate {
            nonnegative(positionMetres); nonnegative(distanceMetres); nonnegative(emissionCost);
            named(edgeId); Objects.requireNonNull(point);
            if (headingErrorDegrees != null) nonnegative(headingErrorDegrees);
            uncertaintyReasons = List.copyOf(uncertaintyReasons);
        }
    }

    public record RuleDecision(Status status, List<String> reasonCodes, List<Long> sourceRecords) {
        public RuleDecision {
            Objects.requireNonNull(status); reasonCodes = List.copyOf(reasonCodes);
            sourceRecords = List.copyOf(sourceRecords);
        }
    }

    public record EdgeOccurrence(int originalEdgeIndex, String edgeId, double startMetres, double endMetres) {
        public EdgeOccurrence {
            named(edgeId); nonnegative(startMetres); nonnegative(endMetres);
            if (endMetres < startMetres) throw invalid("REVERSE_RANGE_NOT_PUBLISHABLE");
        }
    }

    public record Connection(boolean found, double cost, Double distanceMetres,
                             List<EdgeOccurrence> occurrences, List<String> reasonCodes,
                             List<RuleDecision> selectedRuleDecisions,
                             List<RuleDecision> hardExclusions, int searchedStates) {
        public Connection {
            occurrences = List.copyOf(occurrences); reasonCodes = List.copyOf(reasonCodes);
            selectedRuleDecisions = List.copyOf(selectedRuleDecisions); hardExclusions = List.copyOf(hardExclusions);
        }
    }

    public record Selection(Observation observation, Candidate candidate, double confidenceMargin) {}

    public record Interval(double sourceStartMetres, double sourceEndMetres, IntervalKind kind,
                           double transitionCost, List<EdgeOccurrence> referenceOccurrences,
                           List<String> reasonCodes, List<RuleDecision> ruleDecisions) {
        public Interval {
            referenceOccurrences = List.copyOf(referenceOccurrences); reasonCodes = List.copyOf(reasonCodes);
            ruleDecisions = List.copyOf(ruleDecisions);
            if (kind == IntervalKind.SOURCE_FALLBACK && !referenceOccurrences.isEmpty())
                throw invalid("SOURCE_FALLBACK_CANNOT_CLAIM_REFERENCE_PATH");
        }
    }

    public record Result(String revision, double selectedScore, List<Selection> selections,
                         List<Interval> intervals, List<List<Candidate>> candidateLayers,
                         List<String> graphUncertaintyReasons, boolean routeLegalityVerified,
                         boolean geometryAccuracyVerified) {
        public Result {
            selections = List.copyOf(selections); intervals = List.copyOf(intervals);
            candidateLayers = candidateLayers.stream().map(List::copyOf).toList();
            graphUncertaintyReasons = List.copyOf(graphUncertaintyReasons);
            if (routeLegalityVerified || geometryAccuracyVerified) throw invalid("MATCHER_CANNOT_GRANT_ACCURACY_OR_PERMISSION");
        }
    }

    private final Graph input;
    private final List<Edge> edges = new ArrayList<>();
    private final Map<Integer, Edge> byOriginalIndex = new HashMap<>();
    private final Map<String, Edge> byId = new HashMap<>();
    private final Map<String, List<Edge>> outgoing = new HashMap<>();
    private final Map<String, List<TurnFact>> turns = new HashMap<>();
    private final List<String> graphReasons = new ArrayList<>();
    private final SpatialNode spatial;

    public SourceObservationRouteMatcher(Graph input) {
        this.input = Objects.requireNonNull(input);
        Map<String, XY> nodePoints = new HashMap<>();
        Set<String> invalidNodes = new HashSet<>();
        for (DirectedEdge declared : input.edges()) {
            Edge edge = new Edge(declared);
            if (byOriginalIndex.put(declared.originalIndex(), edge) != null || byId.put(declared.id(), edge) != null)
                throw invalid("DUPLICATE_ORIGINAL_EDGE_INDEX_OR_ID");
            edges.add(edge);
            for (boolean start : List.of(true, false)) {
                String node = start ? declared.from() : declared.to();
                String key = nodeKey(declared, node);
                XY point = edge.line.at(start ? 0 : edge.line.length());
                if (invalidNodeId(node)) invalidNodes.add(key);
                XY previous = nodePoints.putIfAbsent(key, point);
                if (previous != null && distance(previous, point) > 1) invalidNodes.add(key);
            }
        }
        for (Edge edge : edges) {
            edge.from = resolvedNode(edge.declared, true, invalidNodes);
            edge.to = resolvedNode(edge.declared, false, invalidNodes);
            if (invalidNodes.contains(nodeKey(edge.declared, edge.declared.from()))
                    || invalidNodes.contains(nodeKey(edge.declared, edge.declared.to()))) {
                edge.uncertainty.add("UNRESOLVED_SOURCE_PORT_ISOLATED");
            }
            outgoing.computeIfAbsent(edge.from, key -> new ArrayList<>()).add(edge);
        }
        if (!invalidNodes.isEmpty()) graphReasons.add("SOURCE_INVALID_OR_CONFLICTING_NODE_IDS_ISOLATED");
        input.turnFacts().forEach(row -> turns.computeIfAbsent(row.incomingEdgeId(), key -> new ArrayList<>()).add(row));
        spatial = edges.isEmpty() ? null : new SpatialNode(new ArrayList<>(edges));
    }

    /** Fixed K4 candidate ranking after confirmed source prohibitions are removed. */
    public List<Candidate> candidates(Observation observation) {
        List<Edge> nearby = new ArrayList<>();
        if (spatial != null) spatial.collect(observation.point(), CANDIDATE_RADIUS_METRES, nearby);
        List<Candidate> result = new ArrayList<>();
        for (Edge edge : nearby) {
            if (edge.prohibited) continue;
            Set<String> allowed = observation.allowedReferenceIds();
            if (allowed != null && !allowed.contains(referenceIdentity(edge))) continue;
            Projection projection = edge.line.project(observation.point());
            if (projection.distance > CANDIDATE_RADIUS_METRES) continue;
            Double angle = angle(observation.heading(), edge.line.tangent(projection.position, 5));
            if (angle != null && angle > 105) continue;
            double emission = square(projection.distance / EMISSION_SIGMA_METRES) + square((angle == null ? 0 : angle) / 50);
            LinkedHashSet<String> unknown = new LinkedHashSet<>(edge.uncertainty);
            unknown.addAll(pendingReasons(edge));
            result.add(new Candidate(edge.declared.originalIndex(), edge.declared.id(), projection.position,
                    projection.point, projection.distance, angle, emission, List.copyOf(unknown)));
        }
        // STRtree tie enumeration is not a portable contract. Original global
        // index is the explicit deterministic tie order; costs are unchanged.
        result.sort(Comparator.comparingDouble(Candidate::emissionCost).thenComparingInt(Candidate::originalEdgeIndex));
        return List.copyOf(result.subList(0, Math.min(CANDIDATE_K, result.size())));
    }

    public Result matchPrepared(List<Observation> supplied) {
        List<Observation> observations = List.copyOf(supplied);
        if (observations.size() < 2) throw invalid("AT_LEAST_TWO_PREPARED_OBSERVATIONS_REQUIRED");
        for (int i = 1; i < observations.size(); i++) {
            if (observations.get(i).sourceProgressMetres() <= observations.get(i - 1).sourceProgressMetres()
                    || observations.get(i).distanceProgressMetres() <= observations.get(i - 1).distanceProgressMetres())
                throw invalid("OBSERVATION_AXES_MUST_BE_STRICTLY_INCREASING");
        }
        List<List<Candidate>> layers = observations.stream().map(this::candidates).toList();
        int count = observations.size();
        double[][] forward = new double[count][];
        int[][] previous = new int[count][];
        List<Connection[][]> matrices = new ArrayList<>();
        Map<ConnectionKey, Connection> cache = new HashMap<>();
        forward[0] = new double[layers.get(0).size() + 1];
        for (int j = 0; j < forward[0].length; j++) forward[0][j] = emission(at(layers.get(0), j));
        for (int i = 1; i < count; i++) {
            List<Candidate> left = layers.get(i - 1), right = layers.get(i);
            Connection[][] matrix = new Connection[left.size() + 1][right.size() + 1];
            forward[i] = new double[right.size() + 1]; previous[i] = new int[right.size() + 1];
            for (int j = 0; j <= right.size(); j++) {
                double best = Double.POSITIVE_INFINITY; int winner = 0;
                for (int k = 0; k <= left.size(); k++) {
                    Candidate a = at(left, k), b = at(right, j);
                    double observed = observations.get(i).distanceProgressMetres() - observations.get(i - 1).distanceProgressMetres();
                    Connection connection = cachedConnection(a, b, observed, cache); matrix[k][j] = connection;
                    double cost = forward[i - 1][k] + connection.cost();
                    if (cost < best) { best = cost; winner = k; }
                }
                forward[i][j] = best + emission(at(right, j)); previous[i][j] = winner;
            }
            matrices.add(matrix);
        }
        int[] selected = new int[count]; selected[count - 1] = minimumIndex(forward[count - 1]);
        double score = forward[count - 1][selected[count - 1]];
        for (int i = count - 2; i >= 0; i--) selected[i] = previous[i + 1][selected[i + 1]];
        double[][] backward = new double[count][]; backward[count - 1] = new double[layers.get(count - 1).size() + 1];
        for (int i = count - 2; i >= 0; i--) {
            backward[i] = new double[layers.get(i).size() + 1];
            for (int k = 0; k < backward[i].length; k++) {
                double best = Double.POSITIVE_INFINITY;
                for (int j = 0; j < backward[i + 1].length; j++) {
                    double value = matrices.get(i)[k][j].cost() + emission(at(layers.get(i + 1), j)) + backward[i + 1][j];
                    best = Math.min(best, value);
                }
                backward[i][k] = best;
            }
        }
        List<Selection> selections = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Candidate chosen = at(layers.get(i), selected[i]); double margin = Double.POSITIVE_INFINITY;
            for (int j = 0; j < forward[i].length; j++) {
                if (!equivalent(chosen, at(layers.get(i), j))) margin = Math.min(margin, forward[i][j] + backward[i][j] - score);
            }
            if (margin == Double.POSITIVE_INFINITY) margin = 99;
            selections.add(new Selection(observations.get(i), chosen, Math.max(0, margin)));
        }
        List<Interval> intervals = new ArrayList<>();
        for (int i = 0; i < count - 1; i++) {
            Selection a = selections.get(i), b = selections.get(i + 1);
            Connection connection = matrices.get(i)[selected[i]][selected[i + 1]];
            LinkedHashSet<String> reasons = new LinkedHashSet<>(connection.reasonCodes());
            String fragmentProblem = a.candidate() != null && b.candidate() != null && connection.found()
                    ? unusableFragmentReason(connection) : null;
            boolean reference = a.candidate() != null && b.candidate() != null && connection.found()
                    && Math.min(a.confidenceMargin(), b.confidenceMargin()) >= MINIMUM_REFERENCE_MARGIN
                    && fragmentProblem == null;
            if (fragmentProblem != null) reasons.add(fragmentProblem);
            if (a.candidate() == null || b.candidate() == null) reasons.add("NULL_OBSERVATION_SOURCE_FALLBACK");
            if ((a.candidate() == null || b.candidate() == null) && !layers.get(i).isEmpty() && !layers.get(i + 1).isEmpty()) {
                boolean allUnavailable = true; LinkedHashSet<String> unavailable = new LinkedHashSet<>();
                for (int left = 0; left < layers.get(i).size(); left++) for (int right = 0; right < layers.get(i + 1).size(); right++) {
                    Connection rejected = matrices.get(i)[left][right];
                    if (rejected.found()) allUnavailable = false;
                    else {
                        unavailable.addAll(rejected.reasonCodes());
                        rejected.hardExclusions().forEach(decision -> unavailable.addAll(decision.reasonCodes()));
                    }
                }
                if (allUnavailable) { reasons.add("ALL_REFERENCE_TRANSITIONS_UNAVAILABLE"); reasons.addAll(unavailable); }
            }
            if (layers.get(i).isEmpty() || layers.get(i + 1).isEmpty()) reasons.add("NO_ELIGIBLE_CANDIDATE_IN_FROZEN_RADIUS");
            if (Math.min(a.confidenceMargin(), b.confidenceMargin()) < MINIMUM_REFERENCE_MARGIN) reasons.add("BELOW_EXISTING_CONFIDENCE_MARGIN");
            if (!reference && connection.occurrences().isEmpty()) reasons.add("NO_PUBLISHABLE_REFERENCE_OCCURRENCE");
            if (a.candidate() != null) reasons.addAll(a.candidate().uncertaintyReasons());
            if (b.candidate() != null) reasons.addAll(b.candidate().uncertaintyReasons());
            intervals.add(new Interval(a.observation().sourceProgressMetres(), b.observation().sourceProgressMetres(),
                    reference ? IntervalKind.REFERENCE_CANDIDATE : IntervalKind.SOURCE_FALLBACK,
                    connection.cost(), reference ? connection.occurrences() : List.of(), List.copyOf(reasons),
                    connection.selectedRuleDecisions()));
        }
        return new Result(REVISION, score, selections, intervals, layers, graphReasons, false, false);
    }

    /** Same fragment substring/join/clean eligibility as the frozen Python path. */
    private String unusableFragmentReason(Connection connection) {
        XY previousPartEnd = null, retained = null; int retainedCount = 0;
        for (EdgeOccurrence occurrence : connection.occurrences()) {
            Edge edge = byOriginalIndex.get(occurrence.originalEdgeIndex());
            XY begin = edge.line.at(occurrence.startMetres()), end = edge.line.at(occurrence.endMetres());
            if (previousPartEnd != null && distance(previousPartEnd, begin) > 1)
                return "REFERENCE_FRAGMENT_SOURCE_JOIN_EXCEEDS_EXISTING_ONE_METRE_LIMIT";
            List<XY> part = new ArrayList<>(); part.add(begin);
            for (int i = 1; i < edge.line.points.size() - 1; i++) {
                if (occurrence.startMetres() < edge.line.chain[i] && edge.line.chain[i] < occurrence.endMetres())
                    part.add(edge.line.points.get(i));
            }
            if (occurrence.endMetres() > occurrence.startMetres()) part.add(end);
            for (XY point : part) if (retained == null || distance(retained, point) > .001) {
                retained = point; retainedCount++;
            }
            previousPartEnd = end;
        }
        return retainedCount < 2 ? "REFERENCE_FRAGMENT_COLLAPSED_AFTER_EXISTING_ONE_MILLIMETRE_CLEAN" : null;
    }

    /** Diagnostic of the same transition used by Viterbi; never a legality grant. */
    public Connection transition(Candidate first, Candidate second, double observedMetres) {
        nonnegative(observedMetres);
        return connection(first, second, observedMetres);
    }

    private Connection cachedConnection(Candidate a, Candidate b, double observed, Map<ConnectionKey, Connection> cache) {
        ConnectionKey key = new ConnectionKey(a == null ? -1 : a.originalEdgeIndex(), a == null ? 0 : a.positionMetres(),
                b == null ? -1 : b.originalEdgeIndex(), b == null ? 0 : b.positionMetres(), observed);
        return cache.computeIfAbsent(key, ignored -> connection(a, b, observed));
    }

    private Connection connection(Candidate a, Candidate b, double observed) {
        if (a == null || b == null) return new Connection(true, a == null && b == null ? 0 : NULL_TRANSITION_COST,
                null, List.of(), List.of("NULL_STATE_NOT_REFERENCE_TRAVEL"), List.of(), List.of(), 0);
        Edge first = candidateEdge(a), second = candidateEdge(b);
        if (first.prohibited || second.prohibited) return absent("PROHIBITED_BOUNDARY_EDGE", List.of(), 0);
        if (first.declared.source() != second.declared.source() || !first.declared.namespace().equals(second.declared.namespace()))
            return absent("CROSS_SOURCE_OR_NAMESPACE_TRANSITION_UNSUPPORTED", List.of(), 0);
        if (first == second && b.positionMetres() + 0.001 >= a.positionMetres()) {
            double distance = Math.max(0, b.positionMetres() - a.positionMetres());
            if (observed >= 8 && distance < observed * 0.25) return absent("IMPLAUSIBLE_COLLAPSED_PROGRESS", List.of(), 0);
            List<EdgeOccurrence> ranges = b.positionMetres() > a.positionMetres()
                    ? List.of(occurrence(first, a.positionMetres(), b.positionMetres())) : List.of();
            LinkedHashSet<String> reasons = new LinkedHashSet<>(first.uncertainty); reasons.addAll(pendingReasons(first));
            reasons.add("EDGE_INTERIOR_NOT_ROUTE_APPROVAL");
            if (ranges.isEmpty()) reasons.add("SAME_EDGE_TOLERANCE_HAS_NO_FORWARD_FRAGMENT");
            return new Connection(true, Math.abs(distance - observed) / 8, distance, ranges, List.copyOf(reasons), List.of(), List.of(), 0);
        }
        double remaining = first.line.length() - a.positionMetres() + b.positionMetres();
        double limit = Math.min(2000, Math.max(120, observed * 4 + 60));
        Search search = shortest(first, second, Math.max(0, limit - remaining));
        if (!search.found) return absent(search.reason, search.excluded, search.states);
        double distance = remaining + search.distance;
        if (observed >= 8 && distance < observed * 0.25) return absent("IMPLAUSIBLE_COLLAPSED_PROGRESS", search.excluded, search.states);
        List<EdgeOccurrence> ranges = new ArrayList<>(); ranges.add(occurrence(first, a.positionMetres(), first.line.length()));
        search.middle.forEach(edge -> ranges.add(occurrence(edge, 0, edge.line.length())));
        ranges.add(occurrence(second, 0, b.positionMetres()));
        LinkedHashSet<String> reasons = new LinkedHashSet<>(first.uncertainty); reasons.addAll(pendingReasons(first));
        reasons.addAll(second.uncertainty); reasons.addAll(pendingReasons(second));
        for (RuleDecision decision : search.decisions) reasons.addAll(decision.reasonCodes());
        if (first.declared.source() == Source.NGII) reasons.add("NATIVE_OPERATING_APPLICABILITY_UNRESOLVED");
        return new Connection(true, Math.abs(distance - observed) / 8, distance, ranges, List.copyOf(reasons), search.decisions, search.excluded, search.states);
    }

    private Search shortest(Edge first, Edge second, double limit) {
        State initial = new State(first.to, first.declared.originalIndex());
        Map<State, Double> costs = new HashMap<>(); Map<State, Previous> previous = new HashMap<>();
        PriorityQueue<Queued> queue = new PriorityQueue<>(Comparator.comparingDouble(Queued::cost).thenComparingLong(Queued::serial));
        long serial = 0; costs.put(initial, 0.0); queue.add(new Queued(0, serial++, initial));
        List<RuleDecision> excluded = new ArrayList<>(); int states = 0;
        while (!queue.isEmpty()) {
            Queued current = queue.remove();
            if (current.cost != costs.getOrDefault(current.state, Double.POSITIVE_INFINITY)) continue;
            if (++states > MAX_SEARCH_STATES) return new Search(false, 0, List.of(), List.of(), excluded, states, "SEARCH_STATE_LIMIT");
            Edge incoming = byOriginalIndex.get(current.state.incoming);
            if (current.state.node.equals(second.from)) {
                RuleDecision goal = turnDecision(incoming, second);
                if (goal.status() != Status.PROHIBITED) {
                    List<Edge> path = new ArrayList<>(); List<RuleDecision> decisions = new ArrayList<>();
                    State cursor = current.state;
                    while (!cursor.equals(initial)) {
                        Previous prior = previous.get(cursor); path.add(prior.edge); decisions.add(prior.decision); cursor = prior.state;
                    }
                    Collections.reverse(path); Collections.reverse(decisions); decisions.add(goal);
                    return new Search(true, current.cost, path, decisions, excluded, states, "FOUND_WITH_EXPLICIT_UNKNOWN");
                }
                excluded.add(goal);
            }
            for (Edge next : outgoing.getOrDefault(current.state.node, List.of())) {
                RuleDecision decision = turnDecision(incoming, next);
                if (decision.status() == Status.PROHIBITED) { excluded.add(decision); continue; }
                double cost = current.cost + next.line.length();
                if (cost > limit) continue;
                State state = new State(next.to, next.declared.originalIndex());
                if (cost >= costs.getOrDefault(state, Double.POSITIVE_INFINITY)) continue;
                costs.put(state, cost); previous.put(state, new Previous(current.state, next, decision));
                queue.add(new Queued(cost, serial++, state));
            }
        }
        return new Search(false, 0, List.of(), List.of(), excluded, states, "NO_PATH_WITH_KNOWN_CONSTRAINTS");
    }

    private RuleDecision turnDecision(Edge first, Edge second) {
        if (first.prohibited || second.prohibited) return decision(Status.PROHIBITED, "EDGE_PROHIBITED_BY_VERIFIED_SOURCE_ATTRIBUTE", List.of());
        if (!first.to.equals(second.from) || !first.declared.namespace().equals(second.declared.namespace())
                || first.declared.source() != second.declared.source())
            return decision(Status.PROHIBITED, "NO_EXPLICIT_SAME_NAMESPACE_CONNECTION", List.of());
        if (first.declared.source() != Source.ITS) return decision(Status.UNKNOWN, "NATIVE_TRANSITION_APPLICABILITY_UNRESOLVED", List.of());
        List<TurnFact> rows = turns.getOrDefault(first.declared.id(), List.of());
        List<TurnFact> exact = rows.stream().filter(r -> r.outgoingEdgeId().equals(second.declared.id())
                && r.viaNode().equals(first.declared.to())).toList();
        List<Long> records = exact.stream().map(TurnFact::sourceRecordIndex).toList();
        Set<String> types = new HashSet<>(); exact.forEach(row -> types.add(row.turnType()));
        if (types.size() > 1) return decision(Status.UNKNOWN, "CONFLICTING_OR_COMPOUND_MOVEMENT_CODES", records);
        if (types.contains("002") || rows.stream().anyMatch(r -> "002".equals(r.turnType()) && r.outgoingEdgeId().equals(second.declared.id())))
            return decision(Status.UNKNOWN, "BUS_EXCEPTION_CONTEXT_UNRESOLVED", records);
        if (exact.stream().anyMatch(r -> !"0".equals(r.operation()))) return decision(Status.UNKNOWN, "TIME_APPLICABILITY_UNRESOLVED", records);
        for (TurnFact row : exact) {
            if (!"DIRECTED_INCIDENT".equals(row.bindingStatus()) || row.startLinks().size() != 1 || row.endLinks().size() != 1
                    || !row.startLinks().get(0).equals(new LinkWitness(first.declared.id(), first.declared.from(), first.declared.to()))
                    || !row.endLinks().get(0).equals(new LinkWitness(second.declared.id(), second.declared.from(), second.declared.to())))
                return decision(Status.UNKNOWN, "TURN_REFERENCE_BINDING_UNRESOLVED", records);
        }
        if (types.stream().anyMatch(type -> type != null && Set.of("003", "101", "102", "103").contains(type)))
            return decision(Status.PROHIBITED, "EXPLICIT_ALL_DAY_DIRECTED_PROHIBITION", records);
        if (first.attributeUnknown || second.attributeUnknown) return decision(Status.UNKNOWN, "EDGE_LEGAL_APPLICABILITY_UNRESOLVED", records);
        if (!first.dimensionReasons.isEmpty() || !second.dimensionReasons.isEmpty())
            return decision(Status.UNKNOWN, "VEHICLE_DIMENSION_APPLICABILITY_UNRESOLVED", records);
        if (!pendingReasons(first).isEmpty()) return decision(Status.UNKNOWN, "NONINCIDENT_OR_CONDITIONAL_CONTEXT_UNRESOLVED", records);
        return decision(Status.UNKNOWN, exact.isEmpty() ? "NO_EXPLICIT_TURN_RULE" : "TURN_PERMISSION_NOT_ESTABLISHED", records);
    }

    private List<String> pendingReasons(Edge edge) {
        LinkedHashSet<String> reasons = new LinkedHashSet<>();
        if (edge.declared.source() == Source.ITS) for (TurnFact row : turns.getOrDefault(edge.declared.id(), List.of())) {
            if (!"DIRECTED_INCIDENT".equals(row.bindingStatus())) reasons.add("NONINCIDENT_VIA_CONTEXT_UNRESOLVED");
            if ("002".equals(row.turnType())) reasons.add("BUS_EXCEPTION_CONTEXT_UNRESOLVED");
            if (!"0".equals(row.operation())) reasons.add("TIME_APPLICABILITY_UNRESOLVED");
        }
        return List.copyOf(reasons);
    }

    private boolean equivalent(Candidate a, Candidate b) {
        if (a == null || b == null) return a == b;
        if (a.originalEdgeIndex() == b.originalEdgeIndex()) return Math.abs(a.positionMetres() - b.positionMetres()) < 1;
        Edge first = candidateEdge(a), second = candidateEdge(b);
        return (first.from.equals(second.from) || first.from.equals(second.to) || first.to.equals(second.from) || first.to.equals(second.to))
                && distance(a.point(), b.point()) < 1;
    }

    private Edge candidateEdge(Candidate candidate) {
        Edge edge = byOriginalIndex.get(candidate.originalEdgeIndex());
        if (edge == null || !edge.declared.id().equals(candidate.edgeId()) || candidate.positionMetres() > edge.line.length())
            throw invalid("CANDIDATE_EDGE_OR_RANGE_MISMATCH");
        return edge;
    }

    private static Candidate at(List<Candidate> layer, int index) { return index == layer.size() ? null : layer.get(index); }
    private static double emission(Candidate candidate) { return candidate == null ? NULL_EMISSION_COST : candidate.emissionCost(); }
    private static int minimumIndex(double[] values) { int best = 0; for (int i = 1; i < values.length; i++) if (values[i] < values[best]) best = i; return best; }
    private static EdgeOccurrence occurrence(Edge edge, double start, double end) { return new EdgeOccurrence(edge.declared.originalIndex(), edge.declared.id(), start, end); }
    private static Connection absent(String reason, List<RuleDecision> exclusions, int states) { return new Connection(false, Double.POSITIVE_INFINITY, null, List.of(), List.of(reason), List.of(), exclusions, states); }
    private static RuleDecision decision(Status status, String reason, List<Long> records) { return new RuleDecision(status, List.of(reason), records); }
    private static String referenceIdentity(Edge edge) {
        if (edge.declared.source() == Source.ITS) return edge.declared.id();
        for (var entry : edge.declared.attributes().entrySet()) if (entry.getKey().equalsIgnoreCase("itslinkid") && entry.getValue() != null && !entry.getValue().toString().isEmpty()) return "ITS:" + entry.getValue();
        return null;
    }
    private static String nodeKey(DirectedEdge edge, String node) { return edge.source() + "\u0000" + edge.namespace() + "\u0000" + node; }
    private static String resolvedNode(DirectedEdge edge, boolean start, Set<String> invalid) {
        String node = start ? edge.from() : edge.to(); String key = nodeKey(edge, node);
        return invalid.contains(key) ? "UNRESOLVED_PORT:" + edge.originalIndex() + (start ? ":F" : ":T") : key;
    }
    private static boolean invalidNodeId(String node) { if (node == null || node.isBlank()) return true; String tail = node.substring(node.lastIndexOf(':') + 1); return Set.of("", "-", "None", "0", "0000000000").contains(tail) || node.startsWith("UNRESOLVED_PORT:"); }
    private static void named(String value) { if (value == null || value.isBlank()) throw invalid("NONEMPTY_ID_REQUIRED"); }
    private static void finite(double value) { if (!Double.isFinite(value)) throw invalid("FINITE_COORDINATE_OR_COST_REQUIRED"); }
    private static void nonnegative(double value) { finite(value); if (value < 0) throw invalid("NONNEGATIVE_VALUE_REQUIRED"); }
    private static IllegalArgumentException invalid(String reason) { return new IllegalArgumentException(reason); }
    private static double square(double value) { return value * value; }
    private static double distance(XY a, XY b) { return Math.hypot(a.x() - b.x(), a.y() - b.y()); }
    private static Double angle(XY a, XY b) { double denominator = Math.hypot(a.x(), a.y()) * Math.hypot(b.x(), b.y()); return denominator <= EPS ? null : Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, (a.x() * b.x() + a.y() * b.y()) / denominator)))); }
    private static Map<String, Object> immutableAttributes(Map<String, Object> attributes) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>(); attributes.forEach((key, value) -> result.put(key, immutableValue(value)));
        return Collections.unmodifiableMap(result);
    }
    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) { LinkedHashMap<Object, Object> copy = new LinkedHashMap<>(); map.forEach((k, v) -> copy.put(k, immutableValue(v))); return Collections.unmodifiableMap(copy); }
        if (value instanceof List<?> list) return Collections.unmodifiableList(list.stream().map(SourceObservationRouteMatcher::immutableValue).toList());
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double || value instanceof java.math.BigInteger || value instanceof java.math.BigDecimal) return value;
        throw invalid("RAW_ATTRIBUTE_VALUE_NOT_IMMUTABLE_JSON");
    }

    private static final class Edge {
        final DirectedEdge declared; final Line line; final boolean prohibited; final boolean attributeUnknown;
        final List<String> dimensionReasons = new ArrayList<>(); final LinkedHashSet<String> uncertainty = new LinkedHashSet<>();
        String from; String to;
        Edge(DirectedEdge declared) {
            this.declared = declared; line = new Line(declared.points());
            if (declared.source() == Source.NGII) { prohibited = false; attributeUnknown = true; uncertainty.add("NGII_TRAVEL_APPLICABILITY_NOT_ESTABLISHED"); return; }
            Object road = declared.attributes().get("ROAD_USE"), restriction = declared.attributes().get("REST_VEH");
            prohibited = "1".equals(road) || "3".equals(restriction);
            attributeUnknown = !prohibited && (!"0".equals(road) || !(restriction instanceof String value && Set.of("0", "1", "2", "4", "5").contains(value)));
            if (prohibited) uncertainty.add("1".equals(road) ? "ROAD_RECORDED_UNUSED" : "BUS_EXPLICITLY_RESTRICTED");
            else if (!"0".equals(road)) uncertainty.add("UNKNOWN_ROAD_USE");
            else if ("6".equals(restriction)) uncertainty.add("OTHER_RESTRICTED_VEHICLE_MEANING_UNRESOLVED");
            else if (attributeUnknown) uncertainty.add("UNSUPPORTED_OR_COMPOUND_RESTRICTION");
            for (String field : List.of("REST_H", "REST_W")) {
                try {
                    double value = Double.parseDouble(String.valueOf(declared.attributes().get(field)));
                    if (!Double.isFinite(value) || value < 0) dimensionReasons.add("SOURCE_DIMENSION_LIMIT_UNAVAILABLE_OR_INVALID:" + field);
                    else if (value > 0) dimensionReasons.add("VERIFIED_VEHICLE_DIMENSIONS_REQUIRED:" + field);
                } catch (NumberFormatException ignored) { dimensionReasons.add("SOURCE_DIMENSION_LIMIT_UNAVAILABLE_OR_INVALID:" + field); }
            }
            uncertainty.addAll(dimensionReasons);
        }
    }

    private static final class Line {
        final List<XY> points; final double[] chain; final double minX, minY, maxX, maxY;
        Line(List<XY> raw) {
            List<XY> cleaned = new ArrayList<>();
            for (XY point : raw) if (cleaned.isEmpty() || distance(cleaned.getLast(), point) > 0.001) cleaned.add(point);
            if (cleaned.size() < 2) throw invalid("ZERO_LENGTH_EDGE");
            points = List.copyOf(cleaned); chain = new double[points.size()];
            double x0 = Double.POSITIVE_INFINITY, y0 = x0, x1 = Double.NEGATIVE_INFINITY, y1 = x1;
            for (int i = 0; i < points.size(); i++) {
                XY point = points.get(i); x0 = Math.min(x0, point.x()); y0 = Math.min(y0, point.y()); x1 = Math.max(x1, point.x()); y1 = Math.max(y1, point.y());
                if (i > 0) chain[i] = chain[i - 1] + distance(points.get(i - 1), point);
            }
            minX = x0; minY = y0; maxX = x1; maxY = y1;
        }
        double length() { return chain[chain.length - 1]; }
        XY at(double position) {
            double value = Math.max(0, Math.min(length(), position)); int i = Arrays.binarySearch(chain, value);
            if (i >= 0) return points.get(i); i = -i - 2;
            XY a = points.get(i), b = points.get(i + 1); double fraction = (value - chain[i]) / (chain[i + 1] - chain[i]);
            return new XY(a.x() + fraction * (b.x() - a.x()), a.y() + fraction * (b.y() - a.y()));
        }
        XY tangent(double position, double window) { XY a = at(position - window), b = at(position + window); return new XY(b.x() - a.x(), b.y() - a.y()); }
        Projection project(XY point) {
            double best = Double.POSITIVE_INFINITY, position = 0; XY projected = points.getFirst();
            for (int i = 0; i < points.size() - 1; i++) {
                XY a = points.get(i), b = points.get(i + 1); double dx = b.x() - a.x(), dy = b.y() - a.y();
                double fraction = Math.max(0, Math.min(1, ((point.x() - a.x()) * dx + (point.y() - a.y()) * dy) / (dx * dx + dy * dy)));
                XY q = new XY(a.x() + fraction * dx, a.y() + fraction * dy); double error = square(point.x() - q.x()) + square(point.y() - q.y());
                if (error < best) { best = error; projected = q; position = chain[i] + fraction * (chain[i + 1] - chain[i]); }
            }
            return new Projection(position, projected, Math.sqrt(best));
        }
    }

    /** Exact bounding-box pruning only; no approximate candidate selection. */
    private static final class SpatialNode {
        final double minX, minY, maxX, maxY; final SpatialNode left, right; final List<Edge> leaf;
        SpatialNode(List<Edge> values) {
            minX = values.stream().mapToDouble(e -> e.line.minX).min().orElseThrow(); minY = values.stream().mapToDouble(e -> e.line.minY).min().orElseThrow();
            maxX = values.stream().mapToDouble(e -> e.line.maxX).max().orElseThrow(); maxY = values.stream().mapToDouble(e -> e.line.maxY).max().orElseThrow();
            if (values.size() <= 16) { leaf = List.copyOf(values); left = null; right = null; }
            else {
                boolean x = maxX - minX >= maxY - minY;
                values.sort(Comparator.comparingDouble((Edge edge) -> x ? edge.line.minX + edge.line.maxX : edge.line.minY + edge.line.maxY).thenComparingInt(e -> e.declared.originalIndex()));
                int half = values.size() / 2; leaf = List.of(); left = new SpatialNode(new ArrayList<>(values.subList(0, half))); right = new SpatialNode(new ArrayList<>(values.subList(half, values.size())));
            }
        }
        void collect(XY point, double radius, List<Edge> result) {
            if (point.x() + radius < minX || point.x() - radius > maxX || point.y() + radius < minY || point.y() - radius > maxY) return;
            if (left == null) result.addAll(leaf); else { left.collect(point, radius, result); right.collect(point, radius, result); }
        }
    }

    private record Projection(double position, XY point, double distance) {}
    private record ConnectionKey(int first, double start, int second, double end, double observed) {}
    private record State(String node, int incoming) {}
    private record Queued(double cost, long serial, State state) {}
    private record Previous(State state, Edge edge, RuleDecision decision) {}
    private record Search(boolean found, double distance, List<Edge> middle, List<RuleDecision> decisions,
                          List<RuleDecision> excluded, int states, String reason) {}
}
