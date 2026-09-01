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
 * <p><b>여기서 재는 시간대는 도착 관측 시각 기준이다.</b> 라벨이 도착에서 나오고 모델도 그렇게 배웠다.
 * 반면 예보를 낼 때는 도착 시각을 모르니 예측 시각으로 시간대를 정할 수밖에 없다. 그래서 두 축이 어긋난다.
 * 지평 12의 중앙 소요가 26.5분이라 08:50 에 예보하고 09:10 에 도착하면 아침과 그 밖으로 갈린다.
 * 어느 쪽으로 맞출지는 api 문서가 미결 1번으로 올려 둔 자리이고 이 코드가 정하지 않는다.
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
