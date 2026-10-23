package com.gustler.backend.routecatalog.api;

import java.time.OffsetDateTime;
import java.util.Optional;

/** 수집에 사용할 현재 노선 버전. 없는 경우 기존 bootstrap 정책으로 노선을 읽어 등록한다. */
public interface CurrentRouteVersion {
    /**
     * 현재 열린 노선 판본을 찾고 없으면 전달 시각을 기준으로 신규 확보를 조율한다. 사용할 판본이 없으면 빈 값이다.
     */
    Optional<RouteReference> currentVersionOf(String sourceRouteId, OffsetDateTime scheduledAt);
}
