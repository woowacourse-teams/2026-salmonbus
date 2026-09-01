package com.gustler.backend.processor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 한 판과 그 판에서 본 차량들.
 *
 * <p>차가 한 대도 없던 판도 빈 목록으로 남는다. 그 빈 판이 있어야 연결이 끊긴 자리를 알아본다.
 * 판이 아예 없는 것과 판은 있는데 이 차가 안 보인 것은 다른 사실이다.
 */
public record ObservedBatch(
    long observationBatchId,
    Instant responseReceivedAt,
    List<TrajectoryObservation> observations
) {

    public ObservedBatch {
        observations = List.copyOf(observations);
    }

    public Optional<TrajectoryObservation> observationOf(
        String vehicleId
    ) {
        return observations.stream()
            .filter(observation -> vehicleId.equals(observation.vehicleId()))
            .findFirst();
    }
}
