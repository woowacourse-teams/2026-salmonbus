package com.gustler.backend.forecasting.domain.model.alighting;

import com.gustler.backend.forecasting.domain.model.ObservedVehicle;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 하나의 관측 시점에서 복원한 입력이다. 여섯 필드는 같은 시점의 관측·누적 상태·고정 통계를 구성한다.
 * 과거 상태를 생략한 단순 시간 창은 받을 수 없으며, 원본 float32 변환 순서를 유지한다.
 */
public final class AlightingAwareFeatureSnapshot {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final long routeVersionId;
    private final int capacity;
    private final AlightingAwareObservation source;
    private final AlightingAwareRouteStatistics statistics;
    private final List<AlightingAwareObservation> history;
    private final List<AlightingAwarePassEvent> events;
    private final Map<String, AlightingAwareVehiclePrefix> prefix;

    public AlightingAwareFeatureSnapshot(
        final long routeVersionId,
        AlightingAwareObservation source,
        final int capacity,
        AlightingAwareRouteStatistics statistics,
        List<AlightingAwareObservation> history,
        List<AlightingAwarePassEvent> events
    ) {
        this(routeVersionId, source, capacity, statistics, history, events, Map.of());
    }

    public AlightingAwareFeatureSnapshot(
        final long routeVersionId,
        AlightingAwareObservation source,
        final int capacity,
        AlightingAwareRouteStatistics statistics,
        List<AlightingAwareObservation> history,
        List<AlightingAwarePassEvent> events,
        Map<String, AlightingAwareVehiclePrefix> prefix
    ) {
        if (!source.hasVehicleIdentity()) {
            throw new IllegalArgumentException("Anonymous observation is not a forecast source");
        }
        this.capacity = capacity;
        this.prefix = Map.copyOf(prefix);
        this.routeVersionId = routeVersionId;
        this.source = source;
        this.statistics = statistics;
        this.history = history.stream().filter(observation -> before(observation, source)).toList();
        this.events = events.stream()
            .filter(event -> isAvailable(event, source))
            .sorted(Comparator.comparing(this::eventTime))
            .toList();
        if (!this.history.contains(source)) {
            throw new IllegalArgumentException("Source observation missing from AlightingAware history");
        }
    }

    private static boolean isAvailable(AlightingAwarePassEvent event, AlightingAwareObservation source) {
        return !event.confirmedAt().isAfter(source.receivedAt()) && before(event.confirmation(), source);
    }

    private static boolean before(AlightingAwareObservation observation, AlightingAwareObservation source) {
        if (observation.receivedAt().isBefore(source.receivedAt())) {
            return true;
        }
        return observation.receivedAt().equals(source.receivedAt()) && observation.batchId() <= source.batchId();
    }

    public int capacity() {
        return capacity;
    }

    public long vehicleObservationId() {
        return source.observationId();
    }

    public AlightingAwareObservation source() {
        return source;
    }

    /** 정산할 운행의 식별자다. 정원은 별도 스냅샷 값으로 고정한다. */
    public Provenance provenance() {
        List<AlightingAwareObservation> own = history.stream()
            .filter(row -> row.vehicleId().equals(source.vehicleId()))
            .sorted(Comparator.comparingLong(AlightingAwareObservation::batchRank)).toList();
        AlightingAwareVehiclePrefix current = originState(own).state();
        return new Provenance(current.tripId());
    }

    public record Provenance(long tripStartObservationId) {
    }

    public ObservedVehicle observation() {
        return new ObservedVehicle(source.vehicleId(), routeVersionId, source.passedStopOrder(), source.receivedAt(),
            source.seats(), knownCrowd());
    }

    private Integer knownCrowd() {
        if (source.crowd() >= 1 && source.crowd() <= 4) {
            return source.crowd();
        }
        return null;
    }

    public AlightingAwareFeatureVector forTarget(final int target) {
        final int horizon = target - source.passedStopOrder();
        validateTarget(target, horizon);
        ZonedDateTime time = source.receivedAt().atZone(SEOUL);
        List<AlightingAwareObservation> own = history.stream()
            .filter(observation -> observation.vehicleId().equals(source.vehicleId()))
            .sorted(Comparator.comparingLong(AlightingAwareObservation::batchRank))
            .toList();
        OriginState origin = originState(own);
        Preceding preceding = preceding(origin);
        Neighbors neighbors = neighbors(target, origin.capacity());
        Map<Integer, QueueState> queues = queues();
        PathFeatures path = path(target, time, queues);
        DayFeatures day = day(origin.tripId());
        QueueEstimate queue = queueEstimate(target, horizon, time, queues, neighbors.betweenSeats());
        final double trend = trend(own, origin);
        double[] base = baseFeatures(target, time, origin, preceding);
        double[] extras = extraFeatures(target, time, origin, neighbors, path, day, queue, trend);
        return vectors(target, time, base, extras);
    }

    private void validateTarget(final int target, final int horizon) {
        if (isUnsupportedTarget(target, horizon)) {
            throw new IllegalArgumentException("Unsupported AlightingAware target");
        }
        if (source.seats() == null) {
            throw new IllegalArgumentException("Unknown current seats");
        }
    }

    private boolean isUnsupportedTarget(final int target, final int horizon) {
        if (horizon < 1 || horizon > 12) {
            return true;
        }
        if (statistics.confirmedProfile() && statistics.stop(target).down() != source.down()) {
            return true;
        }
        return !statistics.stop(target).boardingAllowed();
    }

    private OriginState originState(List<AlightingAwareObservation> own) {
        AlightingAwareVehiclePrefix state = prefix.get(source.vehicleId());
        double slope = 0;
        boolean slopeKnown = false;
        int segmentStart = 0;
        for (int index = 0; index < own.size(); index++) {
            AlightingAwareObservation observation = own.get(index);
            AlightingAwareObservation previous = previous(state);
            slopeKnown = hasKnownSlope(previous, observation);
            slope = seatDifference(previous, observation, slopeKnown);
            if (startsTrendSegment(previous, observation)) {
                segmentStart = index;
            }
            state = AlightingAwareHistoryCompactor.advance(state, observation);
        }
        if (capacity <= 0 || capacity > 70) {
            throw new IllegalArgumentException("Invalid vehicle capacity snapshot");
        }
        return new OriginState(state, slope, slopeKnown, segmentStart, capacity);
    }

    private static AlightingAwareObservation previous(AlightingAwareVehiclePrefix state) {
        if (state == null) {
            return null;
        }
        return state.previous();
    }

    private static boolean hasKnownSlope(AlightingAwareObservation previous, AlightingAwareObservation observation) {
        if (observation.confirmedProfile() && observation.startsConfirmedChainAfter(previous)) {
            return false;
        }
        if (previous == null || observation.batchRank() - previous.batchRank() != 1) {
            return false;
        }
        final int difference = observation.passedStopOrder() - previous.passedStopOrder();
        if (difference < 0 || difference > 1) {
            return false;
        }
        return observation.seats() != null && previous.seats() != null;
    }

    private static double seatDifference(
        AlightingAwareObservation previous,
        AlightingAwareObservation observation,
        final boolean known) {
        if (known) {
            return observation.seats() - previous.seats();
        }
        return 0;
    }

    private static boolean startsTrendSegment(AlightingAwareObservation previous, AlightingAwareObservation observation) {
        if (observation.confirmedProfile()) {
            return observation.startsConfirmedChainAfter(previous);
        }
        if (previous == null || observation.passedStopOrder() < previous.passedStopOrder()) {
            return true;
        }
        return Duration.between(previous.receivedAt(), observation.receivedAt()).compareTo(Duration.ofSeconds(90)) > 0;
    }

    private Preceding preceding(OriginState origin) {
        List<AlightingAwareObservation> entries = entriesAt(source.passedStopOrder()).stream()
            .filter(observation -> observation.receivedAt().isAfter(source.receivedAt().minusSeconds(1800)))
            .filter(observation -> observation.receivedAt().isBefore(origin.entryAt()))
            .sorted(Comparator.comparing(AlightingAwareObservation::receivedAt))
            .toList();
        if (entries.isEmpty()) {
            return Preceding.missing();
        }
        AlightingAwareObservation candidate = entries.getLast();
        if (!hasUniqueKnownPreceding(entries, candidate)) {
            return Preceding.missing();
        }
        return new Preceding(0, indicator(candidate.seats() == 0), (float) candidate.seats() / origin.capacity());
    }

    private boolean hasUniqueKnownPreceding(List<AlightingAwareObservation> entries, AlightingAwareObservation candidate) {
        if (candidate.vehicleId().equals(source.vehicleId()) || candidate.seats() == null) {
            return false;
        }
        return entries.size() < 2 || !entries.get(entries.size() - 2).receivedAt().equals(candidate.receivedAt());
    }

    private List<AlightingAwareObservation> entriesAt(final int passedStop) {
        Map<String, AlightingAwareObservation> lastByVehicle = new HashMap<>();
        prefix.forEach((vehicle, state) -> lastByVehicle.put(vehicle, state.previous()));
        List<AlightingAwareObservation> entries = new ArrayList<>();
        for (AlightingAwareObservation observation : history) {
            AlightingAwareObservation previous = lastByVehicle.put(observation.vehicleId(), observation);
            if (isNewEntry(observation, previous, passedStop)) {
                entries.add(observation);
            }
        }
        return entries;
    }

    private static boolean isNewEntry(AlightingAwareObservation observation, AlightingAwareObservation previous, final int passedStop) {
        if (observation.passedStopOrder() != passedStop) {
            return false;
        }
        if (observation.confirmedProfile() && observation.startsConfirmedChainAfter(previous)) {
            return true;
        }
        return previous == null || previous.passedStopOrder() != passedStop;
    }

    private double trend(List<AlightingAwareObservation> own, OriginState origin) {
        for (int index = origin.segmentStart(); index < own.size() - 1; index++) {
            AlightingAwareObservation back = own.get(index);
            if (back.receivedAt().isBefore(source.receivedAt().minusSeconds(300))) {
                continue;
            }
            if (back.seats() == null) {
                return 0;
            }
            return (float) (source.seats() - back.seats()) / origin.capacity();
        }
        return 0;
    }

    private Neighbors neighbors(final int target, final float capacity) {
        List<AlightingAwareObservation> members = history.stream()
            .filter(observation -> observation.batchId() == source.batchId())
            .sorted(Comparator.comparing(AlightingAwareObservation::vehicleId))
            .toList();
        NeighborAccumulator neighbors = new NeighborAccumulator(source.passedStopOrder(), target, capacity);
        for (AlightingAwareObservation member : members) {
            neighbors.accept(member);
        }
        return neighbors.result();
    }

    private Map<Integer, QueueState> queues() {
        Map<Integer, QueueState> queues = new HashMap<>();
        LocalDate day = source.receivedAt().atZone(SEOUL).toLocalDate();
        for (AlightingAwarePassEvent event : events) {
            if (!eventTime(event).atZone(SEOUL).toLocalDate().equals(day)) {
                continue;
            }
            queues.computeIfAbsent(event.stopOrder(), ignored -> new QueueState()).accept(event, eventTime(event));
        }
        return queues;
    }

    private PathFeatures path(final int target, ZonedDateTime time, Map<Integer, QueueState> queues) {
        String day = statistics.dayType(time.toLocalDate());
        final int minute = minute(time);
        double net = 0;
        double left = 0;
        double full = 0;
        double surplus = 0;
        double seen = 0;
        for (int stop = source.passedStopOrder() + 1; stop <= target; stop++) {
            net += statistics.cell("net_tab", stop, day, minute);
            QueueState queue = queues.get(stop);
            if (queue != null && queue.known(source.receivedAt())) {
                left += queue.left(statistics.rate(stop, day, minute), source.receivedAt());
                full += indicator(queue.full >= 1);
            }
            final int selected = stop;
            List<AlightingAwarePassEvent> window = hourEvents().stream().filter(event -> event.stopOrder() == selected).toList();
            surplus += deviation(window) / (window.size() + 1);
            seen += window.size();
        }
        return new PathFeatures(net, left, full, surplus, seen);
    }

    private DayFeatures day(final long tripId) {
        final boolean down = isDown();
        List<AlightingAwarePassEvent> direction = hourEvents().stream()
            .filter(event -> (event.stopOrder() > statistics.turn()) == down)
            .toList();
        final double directionRatio = deviation(direction) / (direction.size() + 5);
        List<AlightingAwarePassEvent> vehicle = events.stream()
            .filter(event -> event.vehicleId().equals(source.vehicleId()) && event.tripId() == tripId)
            .toList();
        vehicle = vehicle.subList(Math.max(0, vehicle.size() - 5), vehicle.size());
        final double vehicleRatio = deviation(vehicle) / (vehicle.size() + 1);
        return new DayFeatures(directionRatio, vehicleRatio, vehicle.size());
    }

    private boolean isDown() {
        if (source.stopOrder() > statistics.turn()) {
            return true;
        }
        if (source.stopOrder() == statistics.turn() && source.state() == 2) {
            return true;
        }
        return source.stopOrder() == 1 && source.state() != 2;
    }

    private List<AlightingAwarePassEvent> hourEvents() {
        return events.stream()
            .filter(event -> !eventTime(event).isBefore(source.receivedAt().minusSeconds(3600)))
            .toList();
    }

    private Instant eventTime(AlightingAwarePassEvent event) {
        if (statistics.confirmedProfile()) {
            return event.confirmedAt();
        }
        return event.departure().receivedAt();
    }

    private double deviation(List<AlightingAwarePassEvent> events) {
        double value = 0;
        for (AlightingAwarePassEvent event : events) {
            ZonedDateTime time = eventTime(event).atZone(SEOUL);
            value += event.arrival().seats() - event.departure().seats()
                - statistics.typicalNet(event.stopOrder(), statistics.dayType(time.toLocalDate()), minute(time));
        }
        return value;
    }

    private QueueEstimate queueEstimate(
        final int target,
        final int horizon,
        ZonedDateTime time,
        Map<Integer, QueueState> queues,
        final float betweenSeats
    ) {
        QueueState queue = queues.get(target);
        final boolean known = queue != null && queue.known(source.receivedAt());
        final double rate = statistics.rate(target, statistics.dayType(time.toLocalDate()), minute(time));
        if (!known) {
            final double left = rate * 10;
            final double leftMine = Math.max(0, left + rate * horizon * statistics.minutesPerStop() - betweenSeats);
            return new QueueEstimate(0, 90, left, leftMine, false);
        }
        final double since = queue.since(source.receivedAt());
        final double left = Math.max(0, rate * since - queue.boarded);
        final double leftMine = Math.max(0, left + rate * horizon * statistics.minutesPerStop() - betweenSeats);
        return new QueueEstimate(queue.full, since, left, leftMine, true);
    }

    private double[] baseFeatures(
        final int target,
        ZonedDateTime time,
        OriginState origin,
        Preceding preceding
    ) {
        final float capacity = origin.capacity();
        final double morning = indicator(time.getHour() >= 7 && time.getHour() < 9);
        final double evening = indicator(time.getHour() >= 17 && time.getHour() < 20);
        final double position = (double) target / statistics.largestStopOrder();
        double[] base = {
            1, morning, evening, 0, (float) source.seats() / capacity, indicator(source.seats() == 0),
            (float) Math.min(source.seats(), 20) / 20, indicator(source.crowd() == 1), indicator(source.crowd() == 2),
            indicator(source.crowd() == 3), indicator(source.crowd() == 4), capacity / 68, origin.slope(),
            indicator(!origin.slopeKnown()), origin.streak(), preceding.full(), preceding.ratio(), preceding.unknown(),
            0
        };
        double[] features = new double[50];
        System.arraycopy(base, 0, features, 0, 19);
        features[19] = position;
        features[31] = indicator(statistics.stop(target).down());
        fillPositionBasis(features, position, morning, evening);
        return features;
    }

    private static void fillPositionBasis(
        double[] features,
        final double position,
        final double morning,
        final double evening
    ) {
        for (int index = 0; index < 8; index++) {
            final double basis = Math.max(1 - Math.abs(position - (double) index / 7) * 7, 0);
            features[20 + index] = basis;
            features[32 + index] = basis * morning;
            features[41 + index] = basis * evening;
        }
        features[40] = features[31] * morning;
        features[49] = features[31] * evening;
    }

    /** 배열 위치는 학습 계약이므로 수식·float 나눗셈의 순서를 바꾸지 않는다. */
    private double[] extraFeatures(
        final int target,
        ZonedDateTime time,
        OriginState origin,
        Neighbors neighbors,
        PathFeatures path,
        DayFeatures demand,
        QueueEstimate queue,
        final double trend
    ) {
        final float capacity = origin.capacity();
        String day = statistics.dayType(time.toLocalDate());
        final int minute = minute(time);
        return new double[] {
            time.getHour(), target - source.passedStopOrder(), source.seats(), capacity,
            (double) target / statistics.largestStopOrder(), Math.min(queue.full(), 5) / 5, queue.since() / 90,
            indicator(!queue.known()), queue.left() / capacity, Math.min(neighbors.betweenCount(), 4) / 4.0,
            Math.min(neighbors.betweenSeats() / capacity, 3), queue.leftMine() / capacity, indicator(queue.full() >= 1),
            path.net() / capacity, path.left() / capacity, path.full(),
            statistics.cell("full_tab", target, day, minute),
            neighbors.nearGap() / 30, neighbors.nearSeats(), neighbors.behindGap() / 30, trend,
            statistics.cell("dep_tab", target, day, minute) / 70, path.seen() / 10, path.surplus() / capacity,
            (path.net() + path.surplus()) / capacity, demand.directionRatio(), demand.vehicleRatio(),
            demand.vehicleCount(),
            indicator(day.equals("off")), minute, time.getDayOfWeek().getValue() - 1
        };
    }

    private AlightingAwareFeatureVector vectors(
        final int target,
        ZonedDateTime time,
        double[] base,
        double[] extras
    ) {
        double[] seat = new double[88];
        System.arraycopy(base, 0, seat, 0, 50);
        System.arraycopy(extras, 0, seat, 50, 31);
        List<Double> means = statistics.targetMeans(target, band(minute(time)));
        for (int index = 0; index < 3; index++) {
            seat[81 + index] = means.get(index);
        }
        double[] entry = AlightingAwareEntryFeatures.calculate(statistics, target, source.passedStopOrder());
        System.arraycopy(entry, 0, seat, 84, 4);
        castFloat32(seat, 88);
        return new AlightingAwareFeatureVector(seat, probabilityFeatures(target, time, base, extras));
    }

    private double[] probabilityFeatures(
        final int target,
        ZonedDateTime time,
        double[] base,
        double[] extras
    ) {
        double[] probability = new double[80];
        System.arraycopy(base, 0, probability, 0, 50);
        int[] indices = { 12, 5, 6, 7, 9, 10, 8, 11, 13, 14, 15, 22, 23, 24, 25, 26 };
        for (int index = 0; index < indices.length; index++) {
            probability[50 + index] = extras[indices[index]];
        }
        fillTimeFeatures(probability, time);
        castFloat32(probability, 79);
        probability[79] = target - source.passedStopOrder();
        return probability;
    }

    private void fillTimeFeatures(double[] probability, ZonedDateTime time) {
        int[][] bins = { { 5, 7 }, { 7, 8 }, { 8, 9 }, { 9, 11 }, { 11, 14 }, { 14, 17 }, { 17, 18 }, { 18, 19 },
            { 19, 20 }, { 20, 22 } };
        final int hour = time.getHour();
        for (int index = 0; index < 10; index++) {
            probability[66 + index] = indicator(hour >= bins[index][0] && hour < bins[index][1]);
        }
        final double weekend = indicator(statistics.dayType(time.toLocalDate()).equals("off"));
        probability[76] = weekend;
        probability[77] = weekend * indicator(hour >= 7 && hour < 9);
        probability[78] = weekend * indicator(hour >= 17 && hour < 20);
    }

    private static void castFloat32(double[] values, final int length) {
        for (int index = 0; index < length; index++) {
            values[index] = (float) values[index];
        }
    }

    private static String band(final int minute) {
        if (minute >= 360 && minute < 600) {
            return "morning";
        }
        if (minute >= 1020 && minute < 1260) {
            return "evening";
        }
        return "other";
    }

    private static int minute(ZonedDateTime time) {
        return time.getHour() * 60 + time.getMinute();
    }

    private static double indicator(final boolean condition) {
        if (condition) {
            return 1;
        }
        return 0;
    }

    private record OriginState(AlightingAwareVehiclePrefix state, double slope, boolean slopeKnown, int segmentStart, float capacity) {

        private double streak() {
            return state.fullStreak();
        }

        private long tripId() {
            return state.tripId();
        }

        private Instant entryAt() {
            return state.entryAt();
        }
    }

    private record Preceding(double unknown, double full, double ratio) {
        private static Preceding missing() {
            return new Preceding(1, 0, 0);
        }
    }

    /** 같은 경로 집계에서 나온 다섯 입력을 원본 계산 순서대로 보관한다. */
    private record PathFeatures(double net, double left, double full, double surplus, double seen) {
    }

    private record DayFeatures(double directionRatio, double vehicleRatio, int vehicleCount) {
    }

    private record QueueEstimate(double full, double since, double left, double leftMine, boolean known) {
    }

    private record Neighbors(
        double nearGap,
        double nearSeats,
        double behindGap,
        int betweenCount,
        float betweenSeats
    ) {
    }

    /** 같은 배치를 순서대로 읽는 집계 상태다. 같은 거리의 첫 차량을 고르는 순서도 원본 계약이다. */
    private static final class NeighborAccumulator {
        private final int origin;
        private final int target;
        private final float capacity;
        private double nearGap = 30;
        private double nearSeats = 1;
        private double behindGap = 30;
        private int betweenCount;
        private boolean nearFound;
        private float betweenSeats;

        private NeighborAccumulator(final int origin, final int target, final float capacity) {
            this.origin = origin;
            this.target = target;
            this.capacity = capacity;
        }

        private void accept(AlightingAwareObservation member) {
            final int gap = member.passedStopOrder() - origin;
            if (isNearestAhead(gap)) {
                nearFound = true;
                nearGap = gap;
                nearSeats = seatRatio(member);
            }
            if (gap < 0) {
                behindGap = Math.min(behindGap, -gap);
            }
            if (isBetweenWithSeats(member, gap)) {
                betweenCount++;
                betweenSeats += member.seats();
            }
        }

        private boolean isNearestAhead(final int gap) {
            if (gap <= 0 || gap > 30) {
                return false;
            }
            return !nearFound || gap < nearGap;
        }

        private boolean isBetweenWithSeats(AlightingAwareObservation member, final int gap) {
            if (gap <= 0 || member.passedStopOrder() >= target) {
                return false;
            }
            return member.seats() != null;
        }

        private double seatRatio(AlightingAwareObservation member) {
            if (member.seats() == null) {
                return 1;
            }
            return (float) member.seats() / capacity;
        }

        private Neighbors result() {
            return new Neighbors(nearGap, nearSeats, behindGap, betweenCount, betweenSeats);
        }
    }

    private static final class QueueState {
        private Instant clear;
        private double full;
        private double boarded;

        private void accept(AlightingAwarePassEvent event, Instant knownAt) {
            if (event.arrival().seats() >= 1 && event.departure().seats() >= 1) {
                clear = knownAt;
                full = 0;
                boarded = 0;
                return;
            }
            full++;
            boarded += Math.max(event.arrival().seats() - event.departure().seats(), 0);
        }

        private double since(Instant now) {
            return Duration.between(clear, now).toNanos() / 60e9;
        }

        private boolean known(Instant now) {
            return clear != null && since(now) <= 90;
        }

        private double left(final double rate, Instant now) {
            return Math.max(0, rate * since(now) - boarded);
        }
    }
}
