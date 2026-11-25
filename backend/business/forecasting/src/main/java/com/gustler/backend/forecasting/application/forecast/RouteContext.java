package com.gustler.backend.forecasting.application.forecast;

import com.gustler.backend.forecasting.domain.deployment.RuntimeSnapshot;
import com.gustler.backend.forecasting.domain.model.RouteStops;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import java.util.Objects;

/**
 * 배치 하나를 처리할 때 쓰는 노선 버전의 모델 정보다. 처리 하나는 이 값을 한 번 정해 끝까지 쓴다.
 *
 * <p>활성 모델, 활성 버전, 모델이 노선을 지원하는지를 보고 정한다. 통계는 확정 버전이어야 한다.
 *
 * @param statistics 모델이 이 노선에 쓰는 확정 입력 프로필의 통계. 노선 상태의 단위({@code statistics.route()})를 정한다
 * @param stops 배치 노선 버전의 정류장
 * @param runtime 처리에 쓰는 활성 배포와 모델
 */
public record RouteContext(AlightingAwareRouteStatistics statistics, RouteStops stops, RuntimeSnapshot runtime) {

    public RouteContext {
        Objects.requireNonNull(statistics);
        Objects.requireNonNull(stops);
        Objects.requireNonNull(runtime);
    }

    /** 노선 상태의 단위인 노선 이름. */
    public String route() {
        return statistics.route();
    }
}
