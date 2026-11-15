package com.gustler.backend.api.board.application;

import com.gustler.backend.api.board.domain.BoardStop;
import com.gustler.backend.api.route.RouteId;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * 여러 노선의 스냅샷을 {@link #findSnapshot}과 같은 규칙으로 읽는다. 스냅샷이 없는 노선은 결과에 없고,
     * 한 노선에 행이 여럿이면 모두 돌려준다. 행이 여럿인 노선을 실패로 다루는 일은 호출하는 쪽이 맡는다.
     */
    default List<BoardSnapshot> findSnapshots(Collection<RouteId> routeIds) {
        return routeIds.stream()
            .map(this::findSnapshot)
            .flatMap(Optional::stream)
            .toList();
    }

    /**
     * 여러 노선 판본의 정류장을 판본별로 읽는다. 판본마다 {@link #findStops(long)}과 같은 순서다.
     * 정류장이 없는 판본은 결과에 없다.
     */
    default Map<Long, List<BoardStop>> findStops(Collection<Long> routeVersionIds) {
        Map<Long, List<BoardStop>> stops = new LinkedHashMap<>();
        for (Long routeVersionId : routeVersionIds) {
            List<BoardStop> found = findStops(routeVersionId.longValue());
            if (!found.isEmpty()) {
                stops.put(routeVersionId, found);
            }
        }
        return stops;
    }

    /**
     * 여러 관측 묶음의 저장 예측을 묶음별로 읽는다. 묶음마다 {@link #findPredictions(long)}과 같은 행이다.
     * 예측이 없는 묶음은 결과에 없다.
     */
    default Map<Long, List<StoredPrediction>> findPredictions(Collection<Long> observationBatchIds) {
        Map<Long, List<StoredPrediction>> predictions = new LinkedHashMap<>();
        for (Long batchId : observationBatchIds) {
            List<StoredPrediction> found = findPredictions(batchId.longValue());
            if (!found.isEmpty()) {
                predictions.put(batchId, found);
            }
        }
        return predictions;
    }

    /**
     * 새 예보 기록({@code forecast_run}·{@code forecast_record})에서 지정한 관측 묶음의 저장 예측을 읽는다.
     * 버스마다 목표 정류장 배열을 펼쳐 {@link #findPredictions(long)}과 같은 모양의 예측으로 돌려준다.
     *
     * <p>새 예보 기록을 읽지 않는 저장소는 {@link ForecastSource#RECORD} 출처의 스냅샷을 돌려주지 않으므로 이 메서드가
     * 불리지 않는다. 그런 저장소를 위한 기본 구현은 예외를 던진다.
     *
     * @param observationBatchId 관측 묶음
     * @param sourceDate 그 묶음의 날짜 파티션
     */
    default List<StoredPrediction> findRecordedPredictions(final long observationBatchId, LocalDate sourceDate) {
        throw new UnsupportedOperationException("이 저장소는 새 예보 기록을 읽지 않습니다.");
    }

    /**
     * 여러 관측 묶음의 새 예보 기록을 묶음별로 읽는다. 키는 관측 묶음, 값은 그 묶음의 날짜 파티션이다.
     * 묶음마다 {@link #findRecordedPredictions(long, LocalDate)}과 같은 행이며 예측이 없는 묶음은 결과에 없다.
     */
    default Map<Long, List<StoredPrediction>> findRecordedPredictions(Map<Long, LocalDate> sourceDateByBatchId) {
        Map<Long, List<StoredPrediction>> predictions = new LinkedHashMap<>();
        sourceDateByBatchId.forEach((batchId, sourceDate) -> {
            List<StoredPrediction> found = findRecordedPredictions(batchId.longValue(), sourceDate);
            if (!found.isEmpty()) {
                predictions.put(batchId, found);
            }
        });
        return predictions;
    }

    /**
     * 관측 묶음과 노선 판본 짝마다 차량을 읽는다. 키는 관측 묶음, 값은 그 묶음을 읽을 노선 판본이다.
     * 짝마다 {@link #findObservedVehicles(long, long)}과 같은 행이며 차량이 없는 묶음은 결과에 없다.
     */
    default Map<Long, List<BoardVehicleObservation>> findObservedVehicles(Map<Long, Long> routeVersionIdByBatchId) {
        Map<Long, List<BoardVehicleObservation>> vehicles = new LinkedHashMap<>();
        routeVersionIdByBatchId.forEach((batchId, routeVersionId) -> {
            List<BoardVehicleObservation> found = findObservedVehicles(batchId.longValue(), routeVersionId.longValue());
            if (!found.isEmpty()) {
                vehicles.put(batchId, found);
            }
        });
        return vehicles;
    }
}
