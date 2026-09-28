package com.gustler.backend.forecasting.domain.statistics;

import java.util.Objects;

public record StopDemandCellTotals(
    int stopOrder,
    TimeSlot timeSlot,
    double fillRateTotal,
    double netBoardingRateTotal,
    long sampleCount,
    int dayCount
) {

    public StopDemandCellTotals {
        Objects.requireNonNull(timeSlot, "시간대가 필요하다");
        if (dayCount < 1 || sampleCount < dayCount) {
            throw new IllegalArgumentException(
                "날짜 수는 1 이상이고 표본 수를 넘지 않는다: %d, %d".formatted(dayCount, sampleCount));
        }
    }

    public static StopDemandCellTotals ofDay(final DailyStopDemand day) {
        return new StopDemandCellTotals(day.stopOrder(), day.timeSlot(), day.fillRate(), day.netBoardingRate(),
            day.sampleCount(), 1);
    }

    public StopDemandCellTotals plus(final StopDemandCellTotals other) {
        if (stopOrder != other.stopOrder || timeSlot != other.timeSlot) {
            throw new IllegalArgumentException("같은 정류장·시간대의 합계만 더할 수 있다");
        }
        return new StopDemandCellTotals(stopOrder, timeSlot, fillRateTotal + other.fillRateTotal,
            netBoardingRateTotal + other.netBoardingRateTotal, Math.addExact(sampleCount, other.sampleCount),
            Math.addExact(dayCount, other.dayCount));
    }

    public StopDemandMeasurement toMeasurement() {
        return new StopDemandMeasurement(timeSlot, new StopDemandCell(stopOrder, fillRateTotal / dayCount,
            netBoardingRateTotal / dayCount, Math.toIntExact(sampleCount), dayCount));
    }
}
