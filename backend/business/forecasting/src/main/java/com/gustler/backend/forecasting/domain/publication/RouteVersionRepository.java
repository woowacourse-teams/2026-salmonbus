package com.gustler.backend.forecasting.domain.publication;

import java.util.List;

/**
 * 노선 버전과 경유 정류장을 예보용 모델로 읽는 포트.
 *
 * <p>수집 구현을 직접 호출하지 않는다. 같은 route_stop을 읽더라도 별도의 조회 모델을 사용한다.
 */
public interface RouteVersionRepository {

    /** 유효 기간이 종료되지 않은 현재 노선 버전 ID를 조회한다. */
    List<Long> findActiveVersionIds();

    /** 해당 버전의 전체 정류장을 순번 오름차순으로 읽고 Open API 노선 ID를 함께 반환한다. */
    RouteStops readStops(
        long routeVersionId
    );
}
