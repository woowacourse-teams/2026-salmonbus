package com.gustler.backend.forecasting.domain.model;

import com.gustler.backend.forecasting.domain.statistics.StopDemandStatistics;
import com.gustler.backend.forecasting.domain.statistics.TimeSlot;

import java.util.Objects;

/**
 * 좌석 예보 하나를 계산하는 데 필요한 입력.
 *
 * <p><b>모델은 DB를 직접 읽지 않는다.</b> 필요한 입력을 모두 전달받아 계산하므로
 * 백테스트에서도 과거 시점의 입력을 구성해 같은 계산 경로를 사용할 수 있다.
 *
 * <p>관련 입력을 하나의 값으로 묶어 모델에 전달한다. 입력 항목이 늘어날 때도
 * 모델을 호출하는 메서드의 매개변수를 각각 추가하지 않고 이 값에서 관리한다.
 *
 * <p>대상·궤적·통계·정류장 목록은 같은 노선 버전에 속해야 한다.
 * 서로 다른 버전의 입력을 설계행렬에 섞어 계산하지 않도록 생성 시 검사한다.
 *
 * <p>당일 평가가 확정된 예보의 집계도 입력으로 받는다. 모델이 DB에 접근하지 않으므로
 * 기준 시각에 맞춰 조회한 집계를 전달해야 한다. <b>{@code null}이면 만석 확률을 보정하지 않는다.</b>
 * 하루가 시작돼 아직 확정된 결과가 없는 경우가 이에 해당한다.
 *
 * <p><b>시간대도 하나만 든다.</b> 설계행렬의 아침·저녁 열과 셀 통계가 서로 다른 시간대를 쓰면
 * 한 예보 행이 두 시간대의 값을 섞는다. 셀 통계가 자기 시간대를 들고 있어서 여기서 대조한다.
 */
public record SeatForecastInput(
    VehicleStopTarget target,
    VehicleTrajectory trajectory,
    StopDemandStatistics statistics,
    RouteStops stops,
    TimeSlot timeSlot,
    SameDayFullOutcomes sameDayFullOutcomes
) {

    public SeatForecastInput {
        Objects.requireNonNull(target, "예보에는 어느 차량이 어느 정류장에 도착하는지가 있어야 한다");
        Objects.requireNonNull(trajectory, "예보에는 그 차량의 궤적 재료가 있어야 한다");
        Objects.requireNonNull(statistics, "예보에는 셀 통계가 있어야 한다");
        Objects.requireNonNull(stops, "예보에는 그 판본의 정류장 목록이 있어야 한다");
        Objects.requireNonNull(timeSlot, "예보에는 시간대가 있어야 한다");

        if (statistics.timeSlot() != timeSlot) {
            throw new IllegalArgumentException(
                "설계행렬의 시간대와 셀 통계의 시간대가 다르다: %s, %s".formatted(timeSlot, statistics.timeSlot())
            );
        }

        if (!trajectory.observation().equals(target.observation())) {
            throw new IllegalArgumentException(
                "궤적과 대상이 같은 관측에서 나와야 한다: %s, %s"
                    .formatted(trajectory.observation(), target.observation())
            );
        }
        final long routeVersionId = target.observation().routeVersionId();
        if (statistics.routeVersionId() != routeVersionId) {
            throw new IllegalArgumentException(
                "셀 통계가 다른 노선 판본의 것이다: %d, %d".formatted(routeVersionId, statistics.routeVersionId())
            );
        }
        if (stops.routeVersionId() != routeVersionId) {
            throw new IllegalArgumentException(
                "정류장 목록이 다른 노선 판본의 것이다: %d, %d".formatted(routeVersionId, stops.routeVersionId())
            );
        }
    }

    public ObservedVehicle observation() {
        return target.observation();
    }

    /** 같은 노선 버전에서 해당 차량의 최대 관측 잔여석. 비율 계산의 분모로 사용한다. */
    public int maximumSeatsEverObserved() {
        return trajectory.maximumSeatsEverObserved();
    }
}
