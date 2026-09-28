package com.gustler.backend.forecasting.application.statistics;

import com.gustler.backend.forecasting.domain.statistics.DailyStopDemand;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsBaseline;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsRun.FoldCursor;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsRun.ReduceCursor;
import com.gustler.backend.forecasting.domain.statistics.StopDemandCellTotals;
import com.gustler.backend.forecasting.domain.statistics.VehicleHourlyDemand;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DemandStatisticsStore {

    record FoldRow(VehicleHourlyDemand total, Integer capacity) {
    }

    void limitStatementTime();

    DemandStatisticsBaseline baseline(long routeVersionId);

    void recordBaseline(long routeVersionId, boolean initialized, Instant dataUntil);

    Optional<String> nextAccumulationVehicle(long routeVersionId, long inputUntilId, String vehicleCursor,
        long inputCursor);

    void addToCurrentTotals(long routeVersionId, List<VehicleHourlyDemand> increments);

    void registerVehicle(long routeVersionId, String vehicleId);

    int clearStagePage(long routeVersionId, int limit);

    void stageCapacities(long routeVersionId, Instant dataUntil, long inputUntilId);

    List<FoldRow> foldPage(long routeVersionId, Optional<FoldCursor> after, int limit);

    void addToDayStage(long routeVersionId, List<DailyStopDemand> days);

    List<DailyStopDemand> reducePage(long routeVersionId, Optional<ReduceCursor> after, int limit);

    void addToCellStage(long routeVersionId, List<StopDemandCellTotals> cells);

    List<StopDemandCellTotals> stagedCells(long routeVersionId);
}
