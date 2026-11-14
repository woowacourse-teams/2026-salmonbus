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
 * 한 관측 배치의 차량 스냅샷이 함께 쓰는 이력·통과 사건·고정 통계·누적 상태와, 차량·목표와 무관한 입력 계산을 보관한다.
 * 이력 거르기, 사건 정렬, 정류장별 대기 상태, 최근 한 시간 사건, 정류장별·방향별 편차, 같은 배치 차량 목록을
 * 관측 시점(수신 시각과 배치 번호)마다 처음 필요할 때 한 번만 계산한다. 계산식과 순회 순서는 차량마다 따로 계산할 때와 같다.
 * 내부 메모는 동기화하지 않으므로 한 스레드에서 한 배치를 처리하는 동안만 쓴다.
 */
public final class AlightingAwareBatchContext {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final AlightingAwareRouteStatistics statistics;
    private final List<AlightingAwareObservation> history;
    private final List<AlightingAwarePassEvent> events;
    private final Map<String, AlightingAwareVehiclePrefix> prefix;
    private final Map<MomentKey, Moment> moments = new HashMap<>();
    private Map<String, AlightingAwareVehiclePrefix> copiedPrefix;

    /**
     * 인자를 검사하거나 복사하지 않고 보관한다. 거르기·복사와 그 예외는 스냅샷을 만들 때 원래 순서대로 일어난다.
     * 넘긴 목록과 맵은 이 배치를 처리하는 동안 바꾸지 않아야 한다.
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

    /** 같은 수신 시각·배치 번호의 관측이 함께 쓰는 거른 이력과 사건이다. 처음 부를 때 거르기를 한다. */
    Moment momentOf(AlightingAwareObservation source) {
        MomentKey key = new MomentKey(source.receivedAt(), source.batchId());
        Moment moment = moments.get(key);
        if (moment == null) {
            moment = new Moment(source.receivedAt(), source.batchId());
            moments.put(key, moment);
        }
        return moment;
    }

    Instant eventTime(AlightingAwarePassEvent event) {
        if (statistics.confirmedProfile()) {
            return event.confirmedAt();
        }
        return event.departure().receivedAt();
    }

    double deviation(List<AlightingAwarePassEvent> events) {
        double value = 0;
        for (AlightingAwarePassEvent event : events) {
            ZonedDateTime time = eventTime(event).atZone(SEOUL);
            value += event.arrival().seats() - event.departure().seats()
                - statistics.typicalNet(event.stopOrder(), statistics.dayType(time.toLocalDate()), minute(time));
        }
        return value;
    }

    private static int minute(ZonedDateTime time) {
        return time.getHour() * 60 + time.getMinute();
    }

    private record MomentKey(Instant receivedAt, long batchId) {
    }

    /** 한 관측 시점에서 볼 수 있는 이력·사건과, 그 시점에서 차량·목표와 무관하게 정해지는 계산 결과다. */
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
            this.history = AlightingAwareBatchContext.this.history.stream().filter(this::before).toList();
            this.events = AlightingAwareBatchContext.this.events.stream()
                .filter(this::isAvailable)
                .sorted(Comparator.comparing(AlightingAwareBatchContext.this::eventTime))
                .toList();
        }

        private boolean isAvailable(AlightingAwarePassEvent event) {
            return !event.confirmedAt().isAfter(receivedAt) && before(event.confirmation());
        }

        private boolean before(AlightingAwareObservation observation) {
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

        /** 관측 날짜의 사건을 시간 순으로 훑어 만든 정류장별 대기 상태다. 읽기만 한다. */
        Map<Integer, QueueState> queues() {
            if (queues != null) {
                return queues;
            }
            Map<Integer, QueueState> built = new HashMap<>();
            LocalDate day = receivedAt.atZone(SEOUL).toLocalDate();
            for (AlightingAwarePassEvent event : events) {
                if (!eventTime(event).atZone(SEOUL).toLocalDate().equals(day)) {
                    continue;
                }
                built.computeIfAbsent(event.stopOrder(), ignored -> new QueueState()).accept(event, eventTime(event));
            }
            queues = built;
            return queues;
        }

        /** 관측 시각 이전 한 시간 안의 사건이다. */
        List<AlightingAwarePassEvent> hourEvents() {
            if (hourEvents == null) {
                hourEvents = events.stream()
                    .filter(event -> !eventTime(event).isBefore(receivedAt.minusSeconds(3600)))
                    .toList();
            }
            return hourEvents;
        }

        /** 한 정류장의 최근 한 시간 편차 비율과 사건 수를 {@code [편차 / (수 + 1), 수]}로 돌려준다. 돌려준 배열은 바꾸지 않는다. */
        double[] stopSurplus(final int stop) {
            double[] cell = surplusByStop.get(stop);
            if (cell == null) {
                List<AlightingAwarePassEvent> window = hourEvents().stream().filter(event -> event.stopOrder() == stop).toList();
                cell = new double[] { deviation(window) / (window.size() + 1), window.size() };
                surplusByStop.put(stop, cell);
            }
            return cell;
        }

        /** 한 방향의 최근 한 시간 편차 비율이다. */
        double directionRatio(final boolean down) {
            final int index = down ? 1 : 0;
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

    /** 한 정류장의 마지막 여유 통과 이후 만석 통과 수와 탑승 누계다. */
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
