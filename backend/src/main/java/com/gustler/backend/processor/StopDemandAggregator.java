package com.gustler.backend.processor;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 시각별 합을 (정류장, 시간대) 셀로 접는다. DB 를 안 탄다.
 *
 * <p><b>날짜 균등가중이다.</b> 날짜마다 평균을 내고 그 평균들을 다시 단순 평균한다.
 * 수집이 적응형이라 날짜별 관측 수가 726 에서 6,206 까지 벌어지는데, 관측 수로 그냥 가중하면
 * 수요가 아니라 수집 밀도가 통계에 샌다.
 *
 * <p>두 값의 접는 방식이 다르다. 자리가 찬 비율은 <b>비율의 평균</b>이고,
 * 순승차 비율은 <b>합의 비율</b>이다. 뒤쪽은 정원이 다른 차량이 섞인 셀에서 값이 일관되게 하려는 것이라
 * 차량마다 나눠서 평균 내면 안 된다.
 */
public final class StopDemandAggregator {

    private StopDemandAggregator() {
    }

    public static List<StopDemandMeasurement> aggregate(
        List<StopDemandHourlyTotals> hourlyTotals,
        Clock clock
    ) {
        Map<CellKey, Map<LocalDate, DayTotals>> byCell = groupByCellAndDay(hourlyTotals, clock);
        List<StopDemandMeasurement> measurements = new ArrayList<>();
        for (Map.Entry<CellKey, Map<LocalDate, DayTotals>> cell : byCell.entrySet()) {
            measurements.add(measurementOf(cell.getKey(), cell.getValue().values()));
        }
        return List.copyOf(measurements);
    }

    private static Map<CellKey, Map<LocalDate, DayTotals>> groupByCellAndDay(
        List<StopDemandHourlyTotals> hourlyTotals,
        Clock clock
    ) {
        Map<CellKey, Map<LocalDate, DayTotals>> byCell = new LinkedHashMap<>();
        for (StopDemandHourlyTotals hour : hourlyTotals) {
            CellKey key = new CellKey(hour.stopOrder(), TimeSlot.of(hour.arrivedHourStart(), clock));
            LocalDate day = hour.arrivedHourStart().atZone(clock.getZone()).toLocalDate();
            byCell.computeIfAbsent(key, cell -> new LinkedHashMap<>())
                .computeIfAbsent(day, date -> new DayTotals())
                .add(hour);
        }
        return byCell;
    }

    private static StopDemandMeasurement measurementOf(
        CellKey key,
        Iterable<DayTotals> days
    ) {
        double fillRateTotal = 0;
        double netBoardingRateTotal = 0;
        int sampleCount = 0;
        int dayCount = 0;
        for (DayTotals day : days) {
            fillRateTotal += day.fillRate();
            netBoardingRateTotal += day.netBoardingRate();
            sampleCount += day.sampleCount;
            dayCount++;
        }
        return new StopDemandMeasurement(
            key.timeSlot(),
            new StopDemandCell(
                key.stopOrder(),
                fillRateTotal / dayCount,
                netBoardingRateTotal / dayCount,
                sampleCount,
                dayCount));
    }

    private record CellKey(
        int stopOrder,
        TimeSlot timeSlot
    ) {
    }

    /** 하루치 합. 하루 안에서는 관측 수로 가중한다. 그날 실제로 그만큼 지나갔기 때문이다. */
    private static final class DayTotals {

        private double fillRateTotal;
        private double netBoardingTotal;
        private double capacityTotal;
        private int sampleCount;

        private void add(
            StopDemandHourlyTotals hour
        ) {
            fillRateTotal += hour.fillRateTotal();
            netBoardingTotal += hour.netBoardingTotal();
            capacityTotal += hour.capacityTotal();
            sampleCount += hour.sampleCount();
        }

        private double fillRate() {
            return fillRateTotal / sampleCount;
        }

        private double netBoardingRate() {
            return netBoardingTotal / capacityTotal;
        }
    }
}
