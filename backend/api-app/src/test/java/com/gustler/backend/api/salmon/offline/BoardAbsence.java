package com.gustler.backend.api.salmon.offline;

import com.gustler.backend.api.board.application.BoardQueryRepository;
import com.gustler.backend.api.board.application.BoardQueryService;
import com.gustler.backend.api.board.application.BoardSnapshot;
import com.gustler.backend.api.board.application.BoardVehicleObservation;
import com.gustler.backend.api.board.domain.BoardStop;
import com.gustler.backend.api.route.RouteId;
import java.util.List;
import java.util.Optional;

/**
 * 보드의 정류장 방문에 차량이 없는 속 원인. 보드는 걸러낸 뒤의 목록만 내보내므로, 보드가 읽은 것과 같은 관측 묶음을
 * 실행기가 다시 읽어 나눈다. 거르는 조건은 BoardQueryService의 Approach와 같다.
 * <ul>
 *   <li>STOP_NOT_BOARDABLE: 승차할 수 없는 정류장이라 보드가 차량을 담지 않는다.</li>
 *   <li>NOT_IN_BATCH: 그 관측 묶음에 이 차량의 관측이 없다.</li>
 *   <li>ALREADY_PASSED: 관측으로는 차량이 이미 이 정류장을 지났다(모델 거리 0 이하).</li>
 *   <li>BEYOND_HORIZON: 모델 거리가 12정류장보다 멀다.</li>
 *   <li>INCONSISTENT_ORDER: 차량의 정류장 순번과 통과 순번의 관계가 맞지 않아 보드가 뺐다.</li>
 *   <li>BEYOND_NEAREST: 담을 수 있는 차량이지만 가까운 순서로 담는 대수 밖이다.</li>
 *   <li>UNEXPLAINED: 위 어느 것도 아니다. 보드를 읽은 뒤 자료가 바뀌었거나 실행기의 조건이 제품과 어긋난 경우다.</li>
 * </ul>
 */
enum BoardAbsence {
    STOP_NOT_BOARDABLE, NOT_IN_BATCH, ALREADY_PASSED, BEYOND_HORIZON, INCONSISTENT_ORDER, BEYOND_NEAREST,
    UNEXPLAINED;

    /**
     * @param vehiclesOnBoard 보드가 그 정류장 방문에 실제로 담은 차량 수. 담을 수 있는 차량이 이보다 많을 때만 대수 제한으로 본다
     */
    static BoardAbsence of(BoardQueryRepository repository, String routeId, int stopSequence, String vehicleId,
                           int vehiclesOnBoard) {
        Optional<BoardSnapshot> snapshot = repository.findSnapshot(new RouteId(routeId));
        if (snapshot.isEmpty() || snapshot.get().observation().isEmpty()) {
            return UNEXPLAINED;
        }
        long versionId = snapshot.get().routeVersionId();
        Optional<BoardStop> stop = repository.findStops(versionId).stream()
            .filter(candidate -> candidate.sequence() == stopSequence).findFirst();
        if (stop.isEmpty()) {
            return UNEXPLAINED;
        }
        if (!stop.get().boardingAllowed()) {
            return STOP_NOT_BOARDABLE;
        }
        List<BoardVehicleObservation> observed = repository
            .findObservedVehicles(snapshot.get().observation().get().batchId(), versionId);
        Optional<BoardVehicleObservation> vehicle = observed.stream()
            .filter(candidate -> vehicleId.equals(candidate.vehicleId())).findFirst();
        if (vehicle.isEmpty()) {
            return NOT_IN_BATCH;
        }
        Optional<BoardAbsence> filtered = filteredOut(stopSequence, vehicle.get());
        if (filtered.isPresent()) {
            return filtered.get();
        }
        long displayable = observed.stream().filter(candidate -> filteredOut(stopSequence, candidate).isEmpty())
            .count();
        return displayable > vehiclesOnBoard ? BEYOND_NEAREST : UNEXPLAINED;
    }

    /** 보드가 이 차량을 거리 조건으로 걸렀으면 그 원인을, 담을 수 있는 차량이면 빈 값을 돌려준다. */
    static Optional<BoardAbsence> filteredOut(int stopSequence, BoardVehicleObservation vehicle) {
        int modelDistance = stopSequence - vehicle.passedStopOrder();
        int displayDistance = stopSequence - vehicle.stopOrder();
        if (modelDistance < 1) {
            return Optional.of(ALREADY_PASSED);
        }
        if (modelDistance > BoardQueryService.MAX_HORIZON_STOPS) {
            return Optional.of(BEYOND_HORIZON);
        }
        if (displayDistance != modelDistance && displayDistance != modelDistance - 1) {
            return Optional.of(INCONSISTENT_ORDER);
        }
        return Optional.empty();
    }
}
