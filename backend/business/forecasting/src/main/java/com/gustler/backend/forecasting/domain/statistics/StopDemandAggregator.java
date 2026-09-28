package com.gustler.backend.forecasting.domain.statistics;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 시간별 합계를 정류장·시간대별 셀 통계로 집계한다. DB에 접근하지 않는다.
 *
 * <p><b>날짜 균등가중이다.</b> 날짜마다 평균을 내고 그 평균들을 다시 단순 평균한다.
 * 수집이 적응형이라 날짜별 관측 수가 726 에서 6,206 까지 벌어지는데, 관측 수로 그냥 가중하면
 * 수요가 아니라 수집 밀도가 통계에 샌다.
 *
 * <p><b>여기서 재는 시간대는 도착 관측 시각 기준이다.</b> 라벨이 도착에서 나오고 모델도 그렇게 배웠다.
 * 반면 예보 시점에는 도착 시각을 모르므로 예측 시각으로 시간대를 정한다. 두 계산의 시간 기준이 다르다.
 * 예보 거리 12정류장의 중앙 소요 시간이 26.5분이라 08:50에 예보하고 09:10에 도착하면 아침과 그 밖으로 갈린다.
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
        Map<CellKey, Map<LocalDate, DailyStopDemand>> byCell = groupByCellAndDay(hourlyTotals, clock);
        List<StopDemandMeasurement> measurements = new ArrayList<>();
        for (Map<LocalDate, DailyStopDemand> days : byCell.values()) {
            measurements.add(measurementOf(days.values()));
        }
        return List.copyOf(measurements);
    }

    private static Map<CellKey, Map<LocalDate, DailyStopDemand>> groupByCellAndDay(
        List<StopDemandHourlyTotals> hourlyTotals,
        Clock clock
    ) {
        Map<CellKey, Map<LocalDate, DailyStopDemand>> byCell = new LinkedHashMap<>();
        for (StopDemandHourlyTotals hour : hourlyTotals) {
            DailyStopDemand day = DailyStopDemand.of(hour, clock);
            byCell.computeIfAbsent(new CellKey(day.stopOrder(), day.timeSlot()), cell -> new LinkedHashMap<>())
                .merge(day.arrivalDate(), day, DailyStopDemand::plus);
        }
        return byCell;
    }

    private static StopDemandMeasurement measurementOf(
        Collection<DailyStopDemand> days
    ) {
        StopDemandCellTotals cell = null;
        for (DailyStopDemand day : days) {
            StopDemandCellTotals contribution = StopDemandCellTotals.ofDay(day);
            cell = cell == null ? contribution : cell.plus(contribution);
        }
        return cell.toMeasurement();
    }

    private record CellKey(
        int stopOrder,
        TimeSlot timeSlot
    ) {
    }
}
