package com.gustler.backend.processor;

import java.util.List;

/**
 * 관측 표를 processor 자기 형으로 읽는 포트.
 *
 * <p>collector 의 코드를 부르지 않는다. 같은 vehicle_observation 을 읽어도 형이 따로 있다.
 */
public interface VehicleTrajectoryRepository {

    /** 예보가 아직 안 붙은 판을 오래된 것부터. */
    List<PendingForecastBatch> findBatchesAwaitingForecast(
        long routeVersionId,
        int limit
    );

    /** 그 판에 담긴 차량마다 궤적 재료 셋. 차가 없던 판이면 빈 목록이다. */
    List<VehicleTrajectory> readTrajectories(
        long observationBatchId
    );
}
