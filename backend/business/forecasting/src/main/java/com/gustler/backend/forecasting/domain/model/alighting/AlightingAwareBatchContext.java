package com.gustler.backend.forecasting.domain.model.alighting;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 같은 배치의 차량들이 사용하는 이력, 통과 이벤트, 통계와 누적 상태를 보관한다.
 *
 * <p>수신 시각과 배치 번호가 같으면 이력 필터링, 이벤트 정렬, 정류장별 대기 상태,
 * 최근 한 시간 통과 이벤트, 정류장별 편차와 방향별 편차, 배치 차량 목록을 한 번만 계산한다.
 * 차량마다 계산할 때와 계산식 및 순회 순서는 같다.
 * 내부 캐시는 동기화하지 않으므로 한 스레드에서 배치 하나를 처리할 때만 사용한다.
 */
public final class AlightingAwareBatchContext {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final AlightingAwareRouteStatistics statistics;
    private final List<AlightingAwareObservation> history;
    private final List<AlightingAwarePassEvent> events;
    private final Map<String, AlightingAwareVehiclePrefix> prefix;
    private final Map<MomentKey, Moment> moments =
        new HashMap<>();
    private Map<String, AlightingAwareVehiclePrefix> copiedPrefix;

    /**
     * 생성할 때는 인자를 검사하거나 복사하지 않는다. 필터링과 복사는 스냅샷을 만들 때 기존 순서대로 수행한다.
     * 전달한 목록과 맵은 배치를 처리하는 동안 변경하면 안 된다.
     */
    public AlightingAwareBatchContext(
        AlightingAwareRouteStatistics statistics,
        List<AlightingAwareObservation> history,
        List<AlightingAwarePassEvent> events,
        Map<String, AlightingAwareVehiclePrefix> prefix
    ) {
        this.statistics = statistics;
        this.history = history;
        this.events = events;
        this.prefix = prefix;
    }

    AlightingAwareRouteStatistics statistics() {
        return statistics;
    }

    Map<String, AlightingAwareVehiclePrefix> prefix() {
        if (copiedPrefix == null) {
            copiedPrefix = Map.copyOf(prefix);
        }
        return copiedPrefix;
    }

    /** 같은 수신 시각과 배치 번호의 관측에 사용할 이력과 이벤트다. 처음 요청할 때 필터링한다. */
    Moment momentOf(AlightingAwareObservation source) {
        MomentKey key = new MomentKey(
            source.receivedAt(),
            source.batchId()
        );
        return moments.computeIfAbsent(key, instantAndBatch ->
            new Moment(instantAndBatch.receivedAt(), instantAndBatch.batchId()));
    }

    Instant eventTime(AlightingAwarePassEvent event) {
        if (statistics.confirmedProfile()) {
            return event.confirmedAt();
        }
        return event.departure().receivedAt();
    }

    double deviation(List<AlightingAwarePassEvent> events) {
        double totalDeviation = 0;
        for (AlightingAwarePassEvent event : events) {
            ZonedDateTime time = eventTime(event).atZone(SEOUL);
            totalDeviation += event.arrival().seats() - event.departure().seats()
                - statistics.typicalNet(event.stopOrder(), statistics.dayType(time.toLocalDate()), minute(time));
        }
        return totalDeviation;
    }

    private static int minute(ZonedDateTime time) {
        return time.getHour() * 60 + time.getMinute();
    }

    private static int directionIndex(final boolean down) {
        if (down) {
            return 1;
        }
        return 0;
    }

    private record MomentKey(Instant receivedAt, long batchId) {

    }

    /** 관측 시점에 사용할 수 있는 이력과 통과 이벤트, 차량 및 목표 정류장에 관계없이 공유하는 계산 결과다. */
    final class Moment {

        private final Instant receivedAt;
        private final long batchId;
        private final List<AlightingAwareObservation> history;
        private final List<AlightingAwarePassEvent> events;
        private final Map<Integer, double[]> surplusByStop = new HashMap<>();
        private final Double[] directionRatios = new Double[2];
        private List<AlightingAwareObservation> members;
        private Map<Integer, QueueState> queues;
        private List<AlightingAwarePassEvent> hourEvents;

        private Moment(Instant receivedAt, final long batchId) {
            this.receivedAt = receivedAt;
            this.batchId = batchId;
            this.history = AlightingAwareBatchContext.this.history.stream()
                .filter(this::isVisibleAtBatch)
                .toList();
            this.events = AlightingAwareBatchContext.this.events.stream()
                .filter(this::isAvailable)
                .sorted(Comparator.comparing(AlightingAwareBatchContext.this::eventTime))
                .toList();
        }

        private boolean isAvailable(AlightingAwarePassEvent event) {
            return !event.confirmedAt().isAfter(receivedAt) && isVisibleAtBatch(event.confirmation());
        }

        private boolean isVisibleAtBatch(AlightingAwareObservation observation) {
            if (observation.receivedAt().isBefore(receivedAt)) {
                return true;
            }
            return observation.receivedAt().equals(receivedAt) && observation.batchId() <= batchId;
        }

        List<AlightingAwareObservation> history() {
            return history;
        }

        List<AlightingAwarePassEvent> events() {
            return events;
        }

        /** 같은 배치 관측을 차량 식별자 순으로 정렬한 목록이다. */
        List<AlightingAwareObservation> members() {
            if (members == null) {
                members = history.stream()
                    .filter(observation -> observation.batchId() == batchId)
                    .sorted(Comparator.comparing(AlightingAwareObservation::vehicleId))
                    .toList();
            }
            return members;
        }

        /** 관측 날짜의 통과 이벤트를 시간순으로 반영한 정류장별 대기 상태다. 반환한 값은 읽기만 한다. */
        Map<Integer, QueueState> queues() {
            if (queues != null) {
                return queues;
            }
            LocalDate observationDate = receivedAt.atZone(SEOUL).toLocalDate();
            Map<Integer, QueueState> queuesByStop = new HashMap<>();
            for (AlightingAwarePassEvent event : events) {
                Instant passTime = eventTime(event);
                if (!passTime.atZone(SEOUL).toLocalDate().equals(observationDate)) {
                    continue;
                }
                QueueState queue = queuesByStop.computeIfAbsent(
                    event.stopOrder(),
                    ignored -> new QueueState()
                );
                queue.accept(event, passTime);
            }
            queues = queuesByStop;
            return queues;
        }

        /** 관측 시각 이전 한 시간 안의 이벤트다. */
        List<AlightingAwarePassEvent> hourEvents() {
            if (hourEvents == null) {
                hourEvents = events.stream()
                    .filter(event -> !eventTime(event).isBefore(receivedAt.minusSeconds(3600)))
                    .toList();
            }
            return hourEvents;
        }

        /**
         * 한 정류장의 최근 한 시간 편차 비율과 이벤트 수를 {@code [편차 / (수 + 1), 수]}로 반환한다. 반환한 배열은 바꾸지 않는다.
         */
        double[] stopSurplus(final int stop) {
            double[] cell = surplusByStop.get(stop);
            if (cell == null) {
                List<AlightingAwarePassEvent> window = hourEvents().stream()
                    .filter(event -> event.stopOrder() == stop)
                    .toList();
                cell = new double[] { deviation(window) / (window.size() + 1), window.size() };
                surplusByStop.put(stop, cell);
            }
            return cell;
        }

        /** 한 방향의 최근 한 시간 편차 비율이다. */
        double directionRatio(final boolean down) {
            final int index = directionIndex(down);
            Double cached = directionRatios[index];
            if (cached == null) {
                List<AlightingAwarePassEvent> direction = hourEvents().stream()
                    .filter(event -> (event.stopOrder() > statistics.turn()) == down)
                    .toList();
                cached = deviation(direction) / (direction.size() + 5);
                directionRatios[index] = cached;
            }
            return cached;
        }
    }

    /** 마지막으로 좌석 여유가 있었던 통과 이후의 만석 통과 수와 누적 탑승객 수다. */
    static final class QueueState {

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

        double full() {
            return full;
        }

        double boarded() {
            return boarded;
        }

        double since(Instant now) {
            return Duration.between(clear, now).toNanos() / 60e9;
        }

        boolean known(Instant now) {
            return clear != null && since(now) <= 90;
        }

        double left(final double rate, Instant now) {
            return Math.max(0, rate * since(now) - boarded);
        }
    }
}
