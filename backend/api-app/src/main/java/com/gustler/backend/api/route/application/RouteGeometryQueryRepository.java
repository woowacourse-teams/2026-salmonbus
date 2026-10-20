package com.gustler.backend.api.route.application;

import com.gustler.backend.api.route.domain.RouteGeometry.Shape;
import com.gustler.backend.api.route.domain.RouteGeometry.Stop;
import java.util.List;
import java.util.Optional;

/**
 * 지도 조회에 사용할 현재 판본·정류장 방문·원천 도로 형상을 읽는다.
 */
public interface RouteGeometryQueryRepository {
    /**
     * 현재 노선의 형상 조회 기준 판본을 찾는다. 노선이 없으면 빈 값이다.
     */
    Optional<Reference> reference(String routeId);

    /**
     * 지정 노선 판본의 정류장 위치와 순번을 읽는다. 형상이 없더라도 정류장 정보는 별도로 조회한다.
     */
    List<Stop> stops(long versionId);

    /**
     * 지정 노선 판본에 연결된 형상을 찾는다. 아직 발행되지 않았으면 빈 값이다.
     */
    Optional<Shape> shape(long versionId);

    record Reference(String routeId, long versionId, String name, String startPoint, String endPoint,
        Integer turnSequence) {
    }
}
