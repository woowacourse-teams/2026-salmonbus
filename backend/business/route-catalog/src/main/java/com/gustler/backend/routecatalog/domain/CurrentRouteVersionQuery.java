package com.gustler.backend.routecatalog.domain;

import java.util.List;
import java.util.OptionalLong;

/**
 * 현재 노선 판본의 식별과 공개 GBIS 노선 목록을 읽는 경계다.
 */
public interface CurrentRouteVersionQuery {
    /**
     * 현재 판본이 있는 GBIS 노선 식별자를 읽는다. 과거 판본이나 내부 대체 식별자를 섞지 않는다.
     */
    List<String> findCurrentGbisRouteIds();

    /** 현재 사용하는 노선 버전의 ID. 생성된 버전이 없으면 빈 결과를 반환한다. */
    OptionalLong findIdOf(
        String sourceRouteId
    );
}
