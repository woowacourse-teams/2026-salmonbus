package com.gustler.backend.processor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 관측 표를 processor 자기 형으로 읽는 포트.
 *
 * <p>collector 의 코드를 부르지 않는다. 같은 vehicle_observation 을 읽어도 형이 따로 있다.
 */
public interface VehicleTrajectoryRepository {

    /** 예보가 아직 안 붙은 판을 오래된 것부터. {@code notBefore} 보다 오래된 판은 안 준다. */
    List<PendingForecastBatch> findBatchesAwaitingForecast(
        long routeVersionId,
        Instant notBefore,
        int limit
    );

    /**
     * 예보가 아직 안 붙은 판 가운데 가장 오래된 것의 관측 시각. 창을 안 본다.
     *
     * <p>창 밖이라 안 집고 지나간 판이 남아 있는지 알아보는 자리다. 큐가 창으로 걸러 낸 뒤에는
     * 그 판이 조회에 안 나와서, 같은 조건을 창 없이 한 줄만 다시 물어야 보인다.
     */
    Optional<Instant> findOldestAwaitingForecastAt(
        long routeVersionId
    );

    /** 그 판에 담긴 차량마다 궤적 재료 셋. 차가 없던 판이면 빈 목록이다. */
    List<VehicleTrajectory> readTrajectories(
        long observationBatchId
    );
}
