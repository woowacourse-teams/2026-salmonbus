package com.gustler.backend.forecasting.infrastructure.statistics;

import com.gustler.backend.forecasting.domain.statistics.DailyStopDemand;
import com.gustler.backend.forecasting.domain.statistics.DemandSample;
import com.gustler.backend.forecasting.domain.statistics.StopDemandCellTotals;
import com.gustler.backend.forecasting.domain.statistics.StopDemandMeasurement;
import com.gustler.backend.forecasting.domain.statistics.TimeSlot;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/** DB에 접근하지 않는 비교용 파일 계산. 결과 게시와 정원 추정은 수행하지 않는다. */
final class StatisticsFileCalculator {
    private StatisticsFileCalculator() {
    }

    static List<StopDemandMeasurement> calculate(
        Path directory,
        List<StatisticsInputFile> files,
        StatisticsInputScope scope,
        Map<String, Integer> capacities,
        final int maxDailyCells,
        final int maxLineCharacters
    ) {
        validateCalculationRequest(capacities, maxDailyCells);
        Map<DayKey, DailyStopDemand> dailyDemands = readAndVerifyDailyDemands(
            directory, files, scope, capacities, maxDailyCells, maxLineCharacters);
        Map<CellKey, StopDemandCellTotals> cellTotals = aggregateCellTotals(dailyDemands);
        return createSortedMeasurements(cellTotals);
    }

    private static void validateCalculationRequest(Map<String, Integer> capacities, final int maxDailyCells) {
        if (maxDailyCells < 1 || capacities == null) {
            throw new IllegalArgumentException("집계 한도와 차량 정원이 필요하다");
        }
    }

    private static Map<DayKey, DailyStopDemand> readAndVerifyDailyDemands(
        Path directory,
        List<StatisticsInputFile> files,
        StatisticsInputScope scope,
        Map<String, Integer> capacities,
        final int maxDailyCells,
        final int maxLineCharacters
    ) {
        Map<DayKey, DailyStopDemand> dailyDemands = new HashMap<>();
        try (StatisticsInputReader reader = new StatisticsInputReader(directory, files, maxLineCharacters)) {
            Iterator<StatisticsInputRow> accumulatingRows = rowsThatAccumulateDailyDemand(
                reader, scope, capacities, maxDailyCells, dailyDemands);
            StatisticsInputRows.verify(scope, accumulatingRows);
        }
        return dailyDemands;
    }

    private static Iterator<StatisticsInputRow> rowsThatAccumulateDailyDemand(
        StatisticsInputReader reader,
        StatisticsInputScope scope,
        Map<String, Integer> capacities,
        final int maxDailyCells,
        Map<DayKey, DailyStopDemand> dailyDemands
    ) {
        return new Iterator<StatisticsInputRow>() {
            @Override
            public boolean hasNext() {
                return reader.hasNext();
            }

            @Override
            public StatisticsInputRow next() {
                StatisticsInputRow row = reader.next();
                row.sample(scope).ifPresent(sample -> accumulateDailyDemand(
                    sample, scope, capacities, maxDailyCells, dailyDemands));
                return row;
            }
        };
    }

    private static void accumulateDailyDemand(
        DemandSample sample,
        StatisticsInputScope scope,
        Map<String, Integer> capacities,
        final int maxDailyCells,
        Map<DayKey, DailyStopDemand> dailyDemands
    ) {
        final int capacity = requireVehicleCapacity(sample, capacities);
        ZonedDateTime hour = sample.arrivedHourStart().atZone(scope.zone());
        TimeSlot slot = TimeSlot.of(sample.arrivedHourStart(), Clock.fixed(scope.dataUntil(), scope.zone()));
        DayKey key = new DayKey(sample.targetStopOrder(), slot, hour.toLocalDate());
        if (!dailyDemands.containsKey(key) && dailyDemands.size() >= maxDailyCells) {
            throw new IllegalArgumentException("날짜별 집계 개수 한도를 초과했다");
        }
        DailyStopDemand contribution = new DailyStopDemand(key.stopOrder(), key.slot(), key.date(),
            1 - sample.arrivalRemainingSeats() / (double) capacity, sample.netBoarding(), capacity, 1);
        dailyDemands.merge(key, contribution, DailyStopDemand::plus);
    }

    private static int requireVehicleCapacity(DemandSample sample, Map<String, Integer> capacities) {
        Integer capacity = capacities.get(sample.vehicleId());
        if (!matchesVehicleCapacity(sample, capacity)) {
            throw new IllegalArgumentException("표본과 일치하는 차량 정원이 필요하다");
        }
        return capacity;
    }

    private static boolean matchesVehicleCapacity(DemandSample sample, Integer capacity) {
        if (capacity == null || capacity < 1) {
            return false;
        }
        if (sample.predictionRemainingSeats() > capacity) {
            return false;
        }
        return sample.arrivalRemainingSeats() <= capacity;
    }

    private static Map<CellKey, StopDemandCellTotals> aggregateCellTotals(
        Map<DayKey, DailyStopDemand> dailyDemands
    ) {
        Map<CellKey, StopDemandCellTotals> cellTotals = new HashMap<>();
        ArrayList<Entry<DayKey, DailyStopDemand>> orderedDays = new ArrayList<>(dailyDemands.entrySet());
        orderedDays.sort(Entry.comparingByKey(Comparator.comparingInt(DayKey::stopOrder)
            .thenComparing(DayKey::slot).thenComparing(DayKey::date)));
        for (Entry<DayKey, DailyStopDemand> entry : orderedDays) {
            DailyStopDemand day = entry.getValue();
            cellTotals.merge(new CellKey(day.stopOrder(), day.timeSlot()), StopDemandCellTotals.ofDay(day),
                StopDemandCellTotals::plus);
        }
        return cellTotals;
    }

    private static List<StopDemandMeasurement> createSortedMeasurements(
        Map<CellKey, StopDemandCellTotals> cellTotals
    ) {
        return cellTotals.values()
            .stream()
            .sorted(Comparator.comparingInt(StopDemandCellTotals::stopOrder)
                .thenComparing(StopDemandCellTotals::timeSlot))
            .map(StopDemandCellTotals::toMeasurement)
            .toList();
    }

    private record DayKey(int stopOrder, TimeSlot slot, LocalDate date) {
    }
    private record CellKey(int stopOrder, TimeSlot slot) {
    }
}
