package com.gustler.backend.forecasting.domain.statistics;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public record VehicleHourlyDemand(
    String vehicleId,
    Instant arrivedHourStart,
    int targetStopOrder,
    long sampleCount,
    long arrivalSeatsSum,
    long netBoardingSum
) {

    private static final Comparator<Key> KEY_ORDER = Comparator.comparing(Key::vehicleId)
        .thenComparing(Key::arrivedHourStart).thenComparingInt(Key::targetStopOrder);

    public VehicleHourlyDemand {
        Objects.requireNonNull(vehicleId, "차량 ID가 필요하다");
        Objects.requireNonNull(arrivedHourStart, "도착 시간대가 필요하다");
        if (sampleCount <= 0) {
            throw new IllegalArgumentException("표본이 없는 시간별 합계는 만들 수 없다: " + sampleCount);
        }
        if (arrivalSeatsSum < 0) {
            throw new IllegalArgumentException("도착 잔여석 합은 0 이상이어야 한다: " + arrivalSeatsSum);
        }
    }

    public static List<VehicleHourlyDemand> sumOf(final Collection<DemandSample> samples) {
        final Map<Key, long[]> sums = new TreeMap<>(KEY_ORDER);
        for (final DemandSample sample : samples) {
            final long[] sum = sums.computeIfAbsent(
                new Key(sample.vehicleId(), sample.arrivedHourStart(), sample.targetStopOrder()), key -> new long[3]);
            sum[0]++;
            sum[1] += sample.arrivalRemainingSeats();
            sum[2] += sample.netBoarding();
        }
        final List<VehicleHourlyDemand> totals = new ArrayList<>();
        sums.forEach((key, sum) -> totals.add(new VehicleHourlyDemand(
            key.vehicleId(), key.arrivedHourStart(), key.targetStopOrder(), sum[0], sum[1], sum[2])));
        return List.copyOf(totals);
    }

    public DailyStopDemand onDay(final int capacity, final Clock clock) {
        if (capacity < 1) {
            throw new IllegalArgumentException("정원은 1 이상이어야 한다: " + capacity);
        }
        return new DailyStopDemand(targetStopOrder, TimeSlot.of(arrivedHourStart, clock),
            arrivedHourStart.atZone(clock.getZone()).toLocalDate(), sampleCount - arrivalSeatsSum / (double) capacity,
            netBoardingSum, Math.multiplyExact(sampleCount, capacity), sampleCount);
    }

    private record Key(String vehicleId, Instant arrivedHourStart, int targetStopOrder) {
    }
}
