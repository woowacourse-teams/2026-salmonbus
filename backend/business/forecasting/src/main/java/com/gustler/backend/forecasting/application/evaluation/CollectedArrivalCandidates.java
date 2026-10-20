package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.forecasting.domain.evaluation.ArrivalCandidate;
import com.gustler.backend.forecasting.domain.model.ObservedVehicle;
import com.gustler.backend.observations.api.ObservationDeliveryBatch;
import java.util.List;
import java.time.Instant;

/** 새 관측에만 적용한다. 기존 편도 배정은 DB 복원으로 읽고 품질 버전 변경 시 재사용하지 않는다. */
final class CollectedArrivalCandidates {
    private CollectedArrivalCandidates() {
    }

    static List<ArrivalCandidate> from(ObservationDeliveryBatch batch, Integer turn, Instant storedAt) {
        return batch.rows()
            .stream()
            .map(row -> new ArrivalCandidate(row.observationId(),
                new ObservedVehicle(row.vehicleId(), batch.routeVersionId(),
                row.passedStopOrder() == null ? 0 : row.passedStopOrder(), storedAt,
                    row.remainingSeats(), row.crowdLevel()),
            row.remainingSeats() != null && row.remainingSeats() > 70 ? null : direction(row, turn), true))
            .toList();
    }

    // forecast_observation_quality와 같은 규칙. NULL 운행 상태는 SQL의 UNKNOWN을 그대로 반영한다.
    private static long direction(ObservationDeliveryBatch.Row row, Integer turn) {
        Integer stop = row.stopOrder();
        Integer state = row.runningState();
        if (turn != null && stop != null && (stop > turn
                    || (stop.equals(turn) && Integer.valueOf(2).equals(state))
                    || (stop == 1 && state != null && state != 2))) return 1;
        return 0;
    }
}
