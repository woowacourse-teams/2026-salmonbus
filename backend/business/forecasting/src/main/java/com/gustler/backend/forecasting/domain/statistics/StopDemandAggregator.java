package com.gustler.backend.forecasting.domain.statistics;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 시간별 합계를 정류장·시간대별 셀 통계로 집계한다. DB에 접근하지 않는다.
 *
 * <p><b>날짜 균등가중이다.</b> 날짜마다 평균을 낸 뒤 날짜별 평균을 다시 단순 평균한다.
 * 적응형 수집으로 날짜별 관측 수가 726 에서 6,206 까지 차이 나므로 관측 수만으로 가중하면
 * 수요 통계에 수집 밀도가 반영된다.
 *
 * <p><b>여기서 재는 시간대는 도착 관측 시각 기준이다.</b> 라벨이 도착 시점에서 나오고 모델도 같은 기준으로 학습했다.
 * 예보 시점에는 도착 시각을 모르므로 예측 시각으로 시간대를 정한다. 두 계산의 시간 기준이 다르다.
 * 예보 거리 12정류장의 중앙 소요 시간이 26.5분이라 08:50에 예보하고 09:10에 도착하면 아침과 그 밖으로 갈린다.
 * 시간 기준의 통일은 api 문서의 미결 1번 항목이며 이 코드에서 결정하지 않는다.
 *
 * <p>두 값의 집계 방식은 다르다. 자리가 찬 비율은 <b>비율의 평균</b>이고
 * 순승차 비율은 <b>합의 비율</b>이다. 정원이 다른 차량이 섞인 셀에서도 순승차 비율을 일관되게 계산하려면
 * 차량마다 나눠서 평균 내면 안 된다.
 */
public final class StopDemandAggregator {
    private StopDemandAggregator() {
    }

    /**
     * 저장된 승하차 표본을 셀별 수요 측정값으로 집계한다. 입력의 시간·정류장 구분을 유지한다.
     */
    public static List<StopDemandMeasurement> aggregate(
        List<StopDemandHourlyTotals> hourlyTotals,
        Clock clock
    ) {
        Map<CellKey, Map<LocalDate, DailyStopDemand>> byCell = groupByCellAndDay(hourlyTotals, clock);
        List<StopDemandMeasurement> measurements = byCell.values()
            .stream()
            .map(days -> measurementOf(days.values()))
            .collect(Collectors.toCollection(ArrayList::new));
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
            cell = addContribution(cell, contribution);
        }
        return cell.toMeasurement();
    }

    private record CellKey(
        int stopOrder,
        TimeSlot timeSlot
    ) {
    }

    private static StopDemandCellTotals addContribution(
        StopDemandCellTotals cell,
        StopDemandCellTotals contribution
    ) {
        if (cell == null) {
            return contribution;
        }
        return cell.plus(contribution);
    }
}
