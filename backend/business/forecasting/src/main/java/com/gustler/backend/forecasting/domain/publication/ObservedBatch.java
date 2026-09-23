package com.gustler.backend.forecasting.domain.publication;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 수집 배치 하나와 그때 관측한 차량들.
 *
 * <p>관측 차량이 없는 배치도 빈 목록으로 남겨 차량의 관측이 끊긴 시점을 확인한다.
 * 수집 배치 자체가 없는 경우와 배치는 있지만 해당 차량이 보이지 않는 경우는 구분한다.
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
