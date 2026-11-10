package com.gustler.backend.api.salmon.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/** 같은 운행의 관측 차이로 구간 시간을 만들고, 도착 목록에 없는 버스의 도착 시각을 복원한다. */
public final class EtaChain {
    private EtaChain() {
    }

    public record Pattern(String routeId, String routeVersion, String direction, String patternId) {
        public Pattern {
            requireText(routeId);
            requireText(routeVersion);
            requireText(direction);
            requireText(patternId);
        }
    }

    public record Run(Pattern pattern, String vehicleId, String tripId) {
        public Run {
            Objects.requireNonNull(pattern);
            requireText(vehicleId);
            requireText(tripId);
        }
    }

    public record Visit(String stationId, int sequence) {
        public Visit {
            requireText(stationId);
            if (sequence < 1) {
                throw new IllegalArgumentException("Stop sequence must be positive");
            }
        }
    }

    public record Observation(String id, Run run, Visit visit, Instant informationAt,
                              Instant arrivalAt, Optional<Duration> errorBound) {
        public Observation {
            requireText(id);
            Objects.requireNonNull(run);
            Objects.requireNonNull(visit);
            Objects.requireNonNull(informationAt);
            Objects.requireNonNull(arrivalAt);
            requireBound(errorBound);
        }
    }

    public enum Method { SAME_RUN, ALIGNED, STITCHED }

    public enum Reason { RUN_MISMATCH, PATTERN_MISMATCH, SEQUENCE_MISMATCH,
        INFORMATION_MISMATCH, PREDICTION_CONFLICT, COVER_BROKEN, NO_ERROR_BOUND }

    public sealed interface Result<T> permits Known, Missing {
    }

    public record Known<T>(T value) implements Result<T> {
        public Known {
            Objects.requireNonNull(value);
        }
    }

    public record Missing<T>(Reason reason) implements Result<T> {
        public Missing {
            Objects.requireNonNull(reason);
        }
    }

    public record Segment(Pattern pattern, Optional<Run> sourceRun, Visit from, Visit to, Duration travelTime,
                          Optional<Duration> errorBound, List<String> evidence, Method method) {
        public Segment {
            Objects.requireNonNull(pattern);
            Objects.requireNonNull(sourceRun);
            if (sourceRun.isPresent() && !sourceRun.orElseThrow().pattern().equals(pattern)) {
                throw new IllegalArgumentException("Run must use the segment pattern");
            }
            requireSequence(from, to);
            requireNonnegative(travelTime);
            requireBound(errorBound);
            evidence = requireEvidence(evidence);
            Objects.requireNonNull(method);
        }
    }

    public record Arrival(Run run, Visit visit, Instant arrivalAt, Optional<Duration> errorBound,
                          List<String> evidence) {
        public Arrival {
            Objects.requireNonNull(run);
            Objects.requireNonNull(visit);
            Objects.requireNonNull(arrivalAt);
            requireBound(errorBound);
            evidence = requireEvidence(evidence);
        }
    }

    public static Result<Segment> sameRun(Observation from, Observation to) {
        if (!from.informationAt().equals(to.informationAt())) {
            return new Missing<>(Reason.INFORMATION_MISMATCH);
        }
        return difference(from, to, Duration.ZERO, Method.SAME_RUN);
    }

    public static Result<Segment> align(Observation from, Observation to, Duration revisionBound,
                                         String revisionEvidence) {
        requireNonnegative(revisionBound);
        requireText(revisionEvidence);
        if (from.errorBound().isEmpty() || to.errorBound().isEmpty()) {
            return new Missing<>(Reason.NO_ERROR_BOUND);
        }
        Result<Segment> result = difference(from, to, revisionBound, Method.ALIGNED);
        if (result instanceof Known<Segment> known) {
            Segment segment = known.value();
            return new Known<>(new Segment(segment.pattern(), segment.sourceRun(), segment.from(), segment.to(),
                segment.travelTime(), segment.errorBound(),
                List.of(from.id(), to.id(), revisionEvidence), Method.ALIGNED));
        }
        return result;
    }

    private static Result<Segment> difference(Observation from, Observation to,
                                              Duration revisionBound, Method method) {
        if (!from.run().equals(to.run())) {
            return new Missing<>(Reason.RUN_MISMATCH);
        }
        if (from.visit().sequence() >= to.visit().sequence()) {
            return new Missing<>(Reason.SEQUENCE_MISMATCH);
        }
        Duration travel = Duration.between(from.arrivalAt(), to.arrivalAt());
        if (travel.isNegative()) {
            return new Missing<>(Reason.PREDICTION_CONFLICT);
        }
        Optional<Duration> bound = from.errorBound().flatMap(first ->
            to.errorBound().map(second -> first.plus(second).plus(revisionBound)));
        return new Known<>(new Segment(from.run().pattern(), Optional.of(from.run()), from.visit(), to.visit(), travel,
            bound, List.of(from.id(), to.id()), method));
    }

    public static Result<Segment> stitch(List<Segment> segments, Duration transferBound,
                                          String transferEvidence) {
        segments = List.copyOf(segments);
        requireNonnegative(transferBound);
        requireText(transferEvidence);
        if (segments.isEmpty()) {
            return new Missing<>(Reason.COVER_BROKEN);
        }
        Segment first = segments.getFirst();
        Visit end = first.from();
        Duration travel = Duration.ZERO;
        Duration bound = transferBound;
        for (Segment segment : segments) {
            if (!first.pattern().equals(segment.pattern())) {
                return new Missing<>(Reason.PATTERN_MISMATCH);
            }
            if (!end.equals(segment.from())) {
                return new Missing<>(Reason.COVER_BROKEN);
            }
            if (segment.errorBound().isEmpty()) {
                return new Missing<>(Reason.NO_ERROR_BOUND);
            }
            travel = travel.plus(segment.travelTime());
            bound = bound.plus(segment.errorBound().orElseThrow());
            end = segment.to();
        }
        List<String> evidence = Stream.concat(
            segments.stream().flatMap(segment -> segment.evidence().stream()),
            Stream.of(transferEvidence)).distinct().toList();
        return new Known<>(new Segment(first.pattern(), Optional.empty(), first.from(), end, travel,
            Optional.of(bound), evidence, Method.STITCHED));
    }

    public static Result<Arrival> restore(Observation anchor, Segment segment) {
        if (segment.sourceRun().isPresent() && !segment.sourceRun().orElseThrow().equals(anchor.run())) {
            return new Missing<>(Reason.RUN_MISMATCH);
        }
        if (!anchor.run().pattern().equals(segment.pattern())) {
            return new Missing<>(Reason.PATTERN_MISMATCH);
        }
        if (!anchor.visit().equals(segment.from())) {
            return new Missing<>(Reason.COVER_BROKEN);
        }
        if (!anchor.id().equals(segment.evidence().getFirst())) {
            return new Missing<>(Reason.INFORMATION_MISMATCH);
        }
        if (segment.errorBound().isEmpty()) {
            return new Missing<>(Reason.NO_ERROR_BOUND);
        }
        // 복원 시각은 anchor 도착과 구간의 anchor 항이 상쇄되므로, 구간 오차에 든 anchor 오차를 빼야 한다.
        // 관측 id가 관측 하나를 정한다는 전제를 쓴다. 뺀 값이 음수면 같은 관측일 수 없으므로 복원하지 않는다.
        Optional<Duration> bound = anchor.errorBound().map(value ->
            segment.errorBound().orElseThrow().minus(value));
        if (bound.isPresent() && bound.get().isNegative()) {
            return new Missing<>(Reason.INFORMATION_MISMATCH);
        }
        List<String> evidence = Stream.concat(
            Stream.of(anchor.id()), segment.evidence().stream()).distinct().toList();
        return new Known<>(new Arrival(anchor.run(), segment.to(),
            anchor.arrivalAt().plus(segment.travelTime()), bound, evidence));
    }

    private static void requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing identifier or evidence");
        }
    }

    private static void requireNonnegative(Duration value) {
        Objects.requireNonNull(value);
        if (value.isNegative()) {
            throw new IllegalArgumentException("Duration must not be negative");
        }
    }

    private static void requireBound(Optional<Duration> bound) {
        Objects.requireNonNull(bound).ifPresent(EtaChain::requireNonnegative);
    }

    private static void requireSequence(Visit from, Visit to) {
        Objects.requireNonNull(from);
        Objects.requireNonNull(to);
        if (from.sequence() >= to.sequence()) {
            throw new IllegalArgumentException("Visits must follow the route order");
        }
    }

    private static List<String> requireEvidence(List<String> evidence) {
        List<String> copy = List.copyOf(evidence);
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("Missing observation evidence");
        }
        copy.forEach(EtaChain::requireText);
        return copy;
    }
}
