package com.gustler.backend.routegeometry.engine.api;

import static com.gustler.backend.routegeometry.engine.api.SourceObservationRouteMatcher.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Frozen Matcher observation backbone, 20m sampling, 15m heading and mandatory visits. */
public final class SourceObservationPreparation {
    private SourceObservationPreparation() {}
    public enum Role { REAL_SHAPE, BOUND_TYPE2_CONTROL, UNKNOWN }

    public record SourceVertex(int originalPointIndex, XY point, Role role) {
        public SourceVertex {
            if (originalPointIndex < 0 || point == null || role == null) throw invalid("EXPLICIT_SOURCE_VERTEX_REQUIRED");
        }
    }

    /** A non-null BMS prior is caller-bound evidence, never inferred from XY. */
    public record MandatoryVisit(String occurrenceId, double sourceProgressMetres, Set<String> boundBmsReferenceIds) {
        public MandatoryVisit {
            if (occurrenceId == null || occurrenceId.isBlank() || !Double.isFinite(sourceProgressMetres) || sourceProgressMetres < 0)
                throw invalid("MANDATORY_VISIT_OCCURRENCE_AND_SOURCE_PROGRESS_REQUIRED");
            boundBmsReferenceIds = boundBmsReferenceIds == null ? null : Set.copyOf(boundBmsReferenceIds);
        }
    }

    public record SourceAxisPosition(int lowerOriginalIndex, int upperOriginalIndex, double fraction) {}

    public record Prepared(List<Observation> observations, List<SourceVertex> sourceAxisVertices,
                           List<Double> sourceVertexProgressMetres, List<SourceAxisPosition> sourceCorrespondences,
                           List<Integer> providedType2Indices, List<Integer> appliedType2Indices,
                           List<String> notes) {
        public Prepared {
            observations = List.copyOf(observations); sourceAxisVertices = List.copyOf(sourceAxisVertices);
            sourceVertexProgressMetres = List.copyOf(sourceVertexProgressMetres); sourceCorrespondences = List.copyOf(sourceCorrespondences);
            providedType2Indices = List.copyOf(providedType2Indices); appliedType2Indices = List.copyOf(appliedType2Indices); notes = List.copyOf(notes);
        }
    }

    public static Prepared prepare(MetricSpace metricSpace, List<SourceVertex> rawVertices, List<MandatoryVisit> suppliedVisits) {
        if (metricSpace == null) throw invalid("METRIC_SOURCE_SPACE_REQUIRED");
        List<SourceVertex> supplied = List.copyOf(rawVertices); List<MandatoryVisit> visits = List.copyOf(suppliedVisits);
        if (supplied.size() < 2) throw invalid("SOURCE_POLYLINE_REQUIRED");
        for (int i = 1; i < supplied.size(); i++) if (supplied.get(i).originalPointIndex() <= supplied.get(i - 1).originalPointIndex())
            throw invalid("ORIGINAL_SOURCE_POINT_INDICES_MUST_BE_ORDERED_UNIQUE");
        Set<String> occurrenceIds = new HashSet<>();
        for (MandatoryVisit visit : visits) if (!occurrenceIds.add(visit.occurrenceId())) throw invalid("DUPLICATE_VISIT_OCCURRENCE_ID");
        List<String> notes = new ArrayList<>(); List<SourceVertex> source = clean(supplied);
        if (source.size() < 2) throw invalid("SOURCE_POLYLINE_COLLAPSED");
        if (source.size() != supplied.size()) notes.add("ADJACENT_SOURCE_POINTS_CLEANED_FOR_OBSERVATION_AXIS");
        List<Double> original = chain(source); double sourceLength = original.getLast();
        for (MandatoryVisit visit : visits) if (visit.sourceProgressMetres() > sourceLength) throw invalid("MANDATORY_VISIT_OUTSIDE_SOURCE_AXIS");
        List<Integer> provided = source.stream().filter(v -> v.role() == Role.BOUND_TYPE2_CONTROL).map(SourceVertex::originalPointIndex).toList();
        List<SourceVertex> retained = new ArrayList<>(); List<Double> retainedOriginal = new ArrayList<>();
        for (int i = 0; i < source.size(); i++) {
            if (i == 0 || i == source.size() - 1 || source.get(i).role() != Role.BOUND_TYPE2_CONTROL) {
                retained.add(source.get(i)); retainedOriginal.add(original.get(i));
            }
        }
        List<SourceVertex> backbone = clean(retained);
        if (backbone.size() != retained.size()) {
            backbone = source; retainedOriginal = original;
            notes.add("TYPE2_BACKBONE_COLLAPSE_REVERTED_TO_FULL_SOURCE_AXIS");
        }
        Set<Integer> retainedIndices = new HashSet<>(); backbone.forEach(v -> retainedIndices.add(v.originalPointIndex()));
        List<Integer> applied = provided.stream().filter(i -> !retainedIndices.contains(i)).toList();
        List<Double> distanceAxis = chain(backbone); double length = distanceAxis.getLast();
        TreeSet<Double> positions = new TreeSet<>(); positions.add(0.0); positions.add(length);
        for (double position = 0; position < length; position += 20) positions.add(position);
        for (MandatoryVisit visit : visits) positions.add(interpolate(visit.sourceProgressMetres(), retainedOriginal, distanceAxis));
        List<Observation> observations = new ArrayList<>(); List<SourceAxisPosition> correspondences = new ArrayList<>();
        for (double distancePosition : positions) {
            double sourcePosition = interpolate(distancePosition, distanceAxis, retainedOriginal);
            SourceAxisPosition sourceAddress = address(sourcePosition, source, original); correspondences.add(sourceAddress);
            XY point = at(distancePosition, backbone, distanceAxis);
            XY before = at(Math.max(0, distancePosition - 15), backbone, distanceAxis);
            XY after = at(Math.min(length, distancePosition + 15), backbone, distanceAxis);
            double dx = after.x() - before.x(), dy = after.y() - before.y(), norm = Math.hypot(dx, dy);
            XY heading = norm > 1e-7 ? new XY(dx / norm, dy / norm) : new XY(0, 0);
            Set<String> allowed = null; List<String> mandatory = new ArrayList<>();
            for (MandatoryVisit visit : visits) if (Math.abs(visit.sourceProgressMetres() - sourcePosition) < 1e-4) {
                mandatory.add(visit.occurrenceId());
                if (visit.boundBmsReferenceIds() != null) {
                    if (allowed == null) allowed = new LinkedHashSet<>(visit.boundBmsReferenceIds());
                    else allowed.retainAll(visit.boundBmsReferenceIds());
                }
            }
            observations.add(new Observation(sourceAddress.lowerOriginalIndex(), sourcePosition, distancePosition,
                    point, heading, allowed, mandatory, applied));
        }
        return new Prepared(observations, source, original, correspondences, provided, applied, notes);
    }

    private static List<SourceVertex> clean(List<SourceVertex> points) {
        List<SourceVertex> result = new ArrayList<>();
        for (SourceVertex vertex : points) if (result.isEmpty() || distance(result.getLast().point(), vertex.point()) > .001) result.add(vertex);
        return result;
    }
    private static List<Double> chain(List<SourceVertex> points) {
        List<Double> result = new ArrayList<>(); result.add(0.0);
        for (int i = 1; i < points.size(); i++) result.add(result.getLast() + distance(points.get(i - 1).point(), points.get(i).point()));
        return List.copyOf(result);
    }
    private static double interpolate(double value, List<Double> domain, List<Double> values) {
        if (value <= domain.getFirst()) return values.getFirst(); if (value >= domain.getLast()) return values.getLast();
        int left = lowerSegment(value, domain); double fraction = (value - domain.get(left)) / (domain.get(left + 1) - domain.get(left));
        return values.get(left) + fraction * (values.get(left + 1) - values.get(left));
    }
    private static int lowerSegment(double value, List<Double> domain) {
        int left = 0, right = domain.size() - 1;
        while (left + 1 < right) { int middle = (left + right) >>> 1; if (domain.get(middle) <= value) left = middle; else right = middle; }
        return left;
    }
    private static XY at(double value, List<SourceVertex> points, List<Double> chain) {
        if (value <= 0) return points.getFirst().point(); if (value >= chain.getLast()) return points.getLast().point();
        int left = lowerSegment(value, chain); double fraction = (value - chain.get(left)) / (chain.get(left + 1) - chain.get(left));
        XY a = points.get(left).point(), b = points.get(left + 1).point();
        return new XY(a.x() + fraction * (b.x() - a.x()), a.y() + fraction * (b.y() - a.y()));
    }
    private static SourceAxisPosition address(double sourcePosition, List<SourceVertex> source, List<Double> chain) {
        if (sourcePosition >= chain.getLast()) { int end = source.getLast().originalPointIndex(); return new SourceAxisPosition(end, end, 0); }
        int lower = lowerSegment(sourcePosition, chain); double fraction = (sourcePosition - chain.get(lower)) / (chain.get(lower + 1) - chain.get(lower));
        if (fraction == 0) { int index = source.get(lower).originalPointIndex(); return new SourceAxisPosition(index, index, 0); }
        return new SourceAxisPosition(source.get(lower).originalPointIndex(), source.get(lower + 1).originalPointIndex(), fraction);
    }
    private static double distance(XY a, XY b) { return Math.hypot(b.x() - a.x(), b.y() - a.y()); }
    private static IllegalArgumentException invalid(String reason) { return new IllegalArgumentException(reason); }
}
