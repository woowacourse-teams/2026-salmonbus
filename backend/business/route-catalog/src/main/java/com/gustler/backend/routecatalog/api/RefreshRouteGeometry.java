package com.gustler.backend.routecatalog.api;

import java.time.OffsetDateTime;

/**
 * 현재 노선 판본의 도로 형상 수신과 발행을 요청하는 공개 업무 경계다.
 */
public interface RefreshRouteGeometry {
    /**
     * 원천 형상을 읽고 저장 판본과 맞춰 발행한다. 외부 조회와 DB 발행의 트랜잭션 범위를 분리한다.
     */
    Outcome refresh(String routeId, OffsetDateTime requestedAt);

    enum Outcome {
        UPDATED, UNCHANGED, NO_REFERENCE, NO_QUOTA, SOURCE_UNAVAILABLE, REFERENCE_CHANGED
    }
}
