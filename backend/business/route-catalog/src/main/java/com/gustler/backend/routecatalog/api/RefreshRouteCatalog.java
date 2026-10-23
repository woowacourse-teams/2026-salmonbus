package com.gustler.backend.routecatalog.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** 호출 한도 내에서 현재 외부 노선정보를 다시 읽는다. 실패하면 기존 버전은 유지한다. */
public interface RefreshRouteCatalog {
    /** 현재 판본이 있는 GBIS 노선의 공개 ID. 외부 HTTP를 호출하지 않는다. */
    List<String> currentGbisRouteIds();

    /** 이미 받은 이름만 반환한다. 조회를 추가하지 않으며 모르면 빈 문자열이다. */
    default String knownRouteName(String sourceRouteId) {
        return "";
    }

    /**
     * 원천 노선 정보를 읽어 현재 판본을 갱신한다. 할당량 부족이나 사용할 결과가 없으면 빈 값이다.
     */
    Optional<RouteReference> refresh(String sourceRouteId, OffsetDateTime readAt);
}
