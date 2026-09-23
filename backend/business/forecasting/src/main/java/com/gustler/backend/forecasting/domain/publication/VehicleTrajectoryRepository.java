package com.gustler.backend.forecasting.domain.publication;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 관측 테이블을 예보용 모델로 읽는 포트.
 *
 * <p>수집 구현을 직접 호출하지 않는다. 같은 vehicle_observation을 읽더라도 별도의 조회 모델을 사용한다.
 */
public interface VehicleTrajectoryRepository {

    /** 예보가 아직 없는 수집 배치를 오래된 순서로 읽는다. {@code notBefore}보다 오래된 배치는 제외한다. */
    List<PendingForecastBatch> findBatchesAwaitingForecast(
        long routeVersionId,
        Instant notBefore,
        int limit
    );

    /**
     * {@code from}과 {@code until} 사이에서 예보가 없는 수집 배치 중 가장 오래된 관측 시각.
     *
     * <p>예보 처리 시간 범위를 벗어난 수집 배치가 있는지 확인한다. 처리 큐의 시간 조건으로는
     * 해당 배치가 조회되지 않으므로 이 조회에서 별도로 확인한다.
     *
     * <p><b>조회 시작 시각도 제한한다.</b> 이관한 관측에는 예보 완료 표시가 없으므로 종료 시각만
     * 제한하면 가장 오래된 대기 배치로 항상 조회된다. 시작 시각도 제한하면 최근에 처리 범위를 벗어난
     * 배치만 남아 재배포나 DB 지연으로 발생한 처리 지연을 확인할 수 있다.
     */
    Optional<Instant> findOldestLeftBehindAt(
        long routeVersionId,
        Instant from,
        Instant until
    );

    /** 수집 배치의 차량별 궤적 입력 세 가지를 읽는다. 관측 차량이 없으면 빈 목록을 반환한다. */
    List<VehicleTrajectory> readTrajectories(
        long observationBatchId
    );
}
