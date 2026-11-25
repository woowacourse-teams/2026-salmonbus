package com.gustler.backend.forecasting.application.forecast;

import java.util.List;

/**
 * 노선 버전의 실정류장 순번 목록을 반환한다.
 *
 * <p>목록은 통과를 만드는 단계가 쓰는 것과 같아야 한다. 통과는 정렬한 실정류장 목록의 처음과 마지막을 뺀 안쪽 정류장에서만
 * 생기므로 정산 단계는 이 목록으로 통과가 생길 수 없는 목표를 가려낸다.
 */
public interface RouteStructureProvider {

    /**
     * 실정류장 순번을 반환한다.
     *
     * @param routeVersionId 노선 버전
     * @return 실정류장 순번. 순서와 중복은 상관없다
     */
    List<Integer> realStopOrders(long routeVersionId);
}
