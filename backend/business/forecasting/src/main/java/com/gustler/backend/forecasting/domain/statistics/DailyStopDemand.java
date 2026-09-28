package com.gustler.backend.forecasting.domain.statistics;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;

/** 하루치 합. 하루 안에서는 관측 수로 가중한다. 그날 실제로 그만큼 지나갔기 때문이다. */
public record DailyStopDemand(
    int stopOrder,
    TimeSlot timeSlot,
    LocalDate arrivalDate,
    double fillRateTotal,
    long netBoardingTotal,
    long capacityTotal,
    long sampleCount
) {

    public DailyStopDemand {
        Objects.requireNonNull(timeSlot, "시간대가 필요하다");
        Objects.requireNonNull(arrivalDate, "도착 날짜가 필요하다");
        if (sampleCount <= 0) {
            throw new IllegalArgumentException("표본이 없는 날짜 합계는 만들 수 없다: " + sampleCount);
        }
        if (capacityTotal <= 0) {
            throw new IllegalArgumentException("정원 합은 0보다 크다: " + capacityTotal);
        }
    }

    public static DailyStopDemand of(final StopDemandHourlyTotals hour, final Clock clock) {
        return new DailyStopDemand(hour.stopOrder(), TimeSlot.of(hour.arrivedHourStart(), clock),
            hour.arrivedHourStart().atZone(clock.getZone()).toLocalDate(), hour.fillRateTotal(),
            Math.round(hour.netBoardingTotal()), Math.round(hour.capacityTotal()), hour.sampleCount());
    }

    public DailyStopDemand plus(final DailyStopDemand other) {
        if (stopOrder != other.stopOrder || timeSlot != other.timeSlot || !arrivalDate.equals(other.arrivalDate)) {
            throw new IllegalArgumentException("같은 정류장·시간대·날짜의 합계만 더할 수 있다");
        }
        return new DailyStopDemand(stopOrder, timeSlot, arrivalDate, fillRateTotal + other.fillRateTotal,
            Math.addExact(netBoardingTotal, other.netBoardingTotal), Math.addExact(capacityTotal, other.capacityTotal),
            Math.addExact(sampleCount, other.sampleCount));
    }

    public double fillRate() {
        return fillRateTotal / sampleCount;
    }

    public double netBoardingRate() {
        return netBoardingTotal / (double) capacityTotal;
    }
}
