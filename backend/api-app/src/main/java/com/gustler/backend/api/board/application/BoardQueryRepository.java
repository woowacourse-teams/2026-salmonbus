package com.gustler.backend.api.board.application;

import com.gustler.backend.api.board.domain.BoardStop;
import com.gustler.backend.api.route.RouteId;
import java.util.List;
import java.util.Optional;

/**
 * 한 관측 기준의 탑승판에 필요한 노선·정류장·모델·차량·예보를 읽는 경계다.
 */
public interface BoardQueryRepository {
    /**
     * 화면에 사용할 관측·발행 시점을 읽는다. 사용할 스냅샷이 없으면 빈 값을 반환한다.
     */
    Optional<BoardSnapshot> findSnapshot(RouteId routeId);

    /**
     * 지정한 노선 판본의 정류장을 읽는다. 원천 정류장 순번과 예측 대상 순번의 의미를 유지한다.
     */
    List<BoardStop> findStops(long routeVersionId);

    /**
     * 지정한 관측 묶음에 속하는 저장 예측을 읽는다. 조회 중 예측을 새로 계산하거나 저장하지 않는다.
     */
    List<StoredPrediction> findPredictions(long observationBatchId);

    /**
     * 지정한 관측 묶음과 노선 판본에 속하는 차량만 읽는다. 다른 판본의 위치를 섞지 않는다.
     */
    List<BoardVehicleObservation> findObservedVehicles(long observationBatchId, long routeVersionId);
}
