package com.gustler.backend.forecasting.domain.route;

import com.gustler.backend.forecasting.domain.model.RouteStops;

/**
 * 노선 버전과 전체 정류장을 예보용 조회 모델로 읽는다.
 * 수집 구현을 호출하지 않고 별도로 조회한다.
 */
public interface RouteStopsQuery {

    /** 해당 버전의 전체 정류장을 순번 오름차순으로 읽고 Open API 노선 ID를 같이 반환한다. */
    RouteStops readStops(long routeVersionId);
}
