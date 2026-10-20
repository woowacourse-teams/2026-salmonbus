package com.gustler.backend.routecatalog.domain.geometry;

import com.gustler.backend.routecatalog.domain.geometry.RouteGeometrySource.Read;
import java.util.Optional;

/**
 * 형상 발행의 기준 노선 판본을 조회하고 새 수신을 해당 판본에 발행하는 경계다.
 */
public interface RouteGeometryStore {
    /**
     * 형상 발행의 내부 노선·원천 노선·현재 판본 식별자를 찾는다. 기준 판본이 없으면 빈 값이다.
     */
    Optional<Reference> current(String publicRouteId);

    /**
     * 읽은 기준 판본에 맞는 형상만 발행한다. 조회 중 판본이 바뀐 경우도 발행 결과에서 구별한다.
     */
    Publication publish(Reference reference, Read read);

    record Reference(long routeId, String sourceRouteId, long versionId) {
    }
    enum Publication {
        UPDATED, UNCHANGED, REFERENCE_CHANGED
    }
}
