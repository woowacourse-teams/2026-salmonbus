package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.forecasting.domain.evaluation.ArrivalCandidate;
import com.gustler.backend.forecasting.domain.model.ObservedVehicle;
import com.gustler.backend.observations.api.ObservationDeliveryBatch;
import java.time.Instant;
import java.util.List;

/** 수집된 새 관측을 정산의 도착 후보로 변환한다. */
final class CollectedArrivalCandidates {
    private CollectedArrivalCandidates() {
    }

    static List<ArrivalCandidate> from(ObservationDeliveryBatch batch, Integer turn, Instant storedAt) {
        return batch.rows().stream()
            .map(row -> new ArrivalCandidate(row.observationId(), new ObservedVehicle(row.vehicleId(),
                batch.routeVersionId(), passedStopOrder(row), storedAt, row.remainingSeats(), row.crowdLevel()),
                direction(row, turn)))
            .toList();
    }

    // operational_observation와 같은 규칙. NULL 운행 상태는 SQL의 UNKNOWN을 그대로 반영한다.
    private static long direction(ObservationDeliveryBatch.Row row, Integer turn) {
        Integer stop = row.stopOrder();
        Integer state = row.runningState();
        if (isInboundObservation(turn, stop, state)) {
            return 1;
        }
        return 0;
    }

    private static boolean isInboundObservation(Integer turn, Integer stop, Integer state) {
        if (turn == null || stop == null) {
            return false;
        }
        if (stop > turn) {
            return true;
        }
        if (stop.equals(turn) && Integer.valueOf(2).equals(state)) {
            return true;
        }
        return isReturningAtFirstStop(stop, state);
    }

    private static boolean isReturningAtFirstStop(final int stop, Integer state) {
        return stop == 1 && Integer.valueOf(1).equals(state);
    }

    private static int passedStopOrder(ObservationDeliveryBatch.Row row) {
        if (row.passedStopOrder() == null) {
            return 0;
        }
        return row.passedStopOrder();
    }
}
