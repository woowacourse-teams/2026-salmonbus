package com.gustler.backend.routecatalog.domain;

/**
 * 수신한 공개 노선의 저장 식별을 확보한다.
 * 판본 생성과 외부 수신은 다른 경계가 담당한다.
 */
public interface RouteRegistry {
    /** 노선 행을 확보하고 ID를 반환한다. 이미 있으면 이름만 상류에서 받은 값으로 갱신한다. */
    long register(
        UpstreamRoute route
    );
}
