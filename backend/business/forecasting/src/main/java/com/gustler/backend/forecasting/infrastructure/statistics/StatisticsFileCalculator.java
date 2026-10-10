package com.gustler.backend.forecasting.infrastructure.statistics;

import com.gustler.backend.forecasting.domain.statistics.DailyStopDemand;
import com.gustler.backend.forecasting.domain.statistics.StopDemandCellTotals;
import com.gustler.backend.forecasting.domain.statistics.StopDemandMeasurement;
import com.gustler.backend.forecasting.domain.statistics.TimeSlot;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** DB에 접근하지 않는 비교용 파일 계산. 결과 게시와 정원 추정은 수행하지 않는다. */
final class StatisticsFileCalculator {
    private StatisticsFileCalculator() { }

    static List<StopDemandMeasurement> calculate(Path directory, List<StatisticsInputFile> files,
        StatisticsInputScope scope, Map<String, Integer> capacities, int maxDailyCells, int maxLineCharacters) {
        if (maxDailyCells < 1 || capacities == null) {
            throw new IllegalArgumentException("집계 한도와 차량 정원이 필요하다");
        }
        Map<DayKey, DailyStopDemand> days = new HashMap<>();
        try (var reader = new StatisticsInputReader(directory, files, maxLineCharacters)) {
            var accumulating = new Iterator<StatisticsInputRow>() {
                @Override public boolean hasNext() { return reader.hasNext(); }
                @Override public StatisticsInputRow next() {
                    var row = reader.next();
                    row.sample(scope).ifPresent(sample -> {
                        Integer capacity = capacities.get(sample.vehicleId());
                        if (capacity == null || capacity < 1
                            || sample.predictionRemainingSeats() > capacity
                            || sample.arrivalRemainingSeats() > capacity) {
                            throw new IllegalArgumentException("표본과 일치하는 차량 정원이 필요하다");
                        }
                        var hour = sample.arrivedHourStart().atZone(scope.zone());
                        var slot = TimeSlot.of(sample.arrivedHourStart(), java.time.Clock.fixed(scope.dataUntil(), scope.zone()));
                        var key = new DayKey(sample.targetStopOrder(), slot, hour.toLocalDate());
                        if (!days.containsKey(key) && days.size() >= maxDailyCells) {
                            throw new IllegalArgumentException("날짜별 집계 개수 한도를 초과했다");
                        }
                        var contribution = new DailyStopDemand(key.stopOrder(), key.slot(), key.date(),
                            1 - sample.arrivalRemainingSeats() / (double) capacity,
                            sample.netBoarding(), capacity, 1);
                        days.merge(key, contribution, DailyStopDemand::plus);
                    });
                    return row;
                }
            };
            // 건수·중복·파일 변경 검사까지 통과한 경우에만 결과를 반환한다.
            StatisticsInputRows.verify(scope, accumulating);
        }
        Map<CellKey, StopDemandCellTotals> cells = new HashMap<>();
        var orderedDays = new ArrayList<>(days.entrySet());
        orderedDays.sort(Map.Entry.comparingByKey(Comparator.comparingInt(DayKey::stopOrder)
            .thenComparing(DayKey::slot).thenComparing(DayKey::date)));
        for (var entry : orderedDays) {
            var day = entry.getValue();
            cells.merge(new CellKey(day.stopOrder(), day.timeSlot()), StopDemandCellTotals.ofDay(day),
                StopDemandCellTotals::plus);
        }
        return cells.values().stream().sorted(Comparator.comparingInt(StopDemandCellTotals::stopOrder)
            .thenComparing(StopDemandCellTotals::timeSlot)).map(StopDemandCellTotals::toMeasurement).toList();
    }

    private record DayKey(int stopOrder, TimeSlot slot, LocalDate date) { }
    private record CellKey(int stopOrder, TimeSlot slot) { }
}
