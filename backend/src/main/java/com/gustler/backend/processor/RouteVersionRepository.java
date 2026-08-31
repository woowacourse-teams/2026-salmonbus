package com.gustler.backend.processor;

import java.util.List;

/**
 * 노선 판본과 그 경유 정류장를 processor 자기 형으로 읽는 포트.
 *
 * <p>collector 의 코드를 부르지 않는다. 같은 route_stop 을 읽어도 형이 따로 있다.
 */
public interface RouteVersionRepository {

    /** 지금 쓰는 판본. 유효 기간이 안 닫힌 것이다. */
    List<Long> findActiveVersionIds();

    /** 그 판본이 지나는 정류장 전부. 순번 오름차순이다. */
    RouteStops readStops(
        long routeVersionId
    );
}
