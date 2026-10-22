package com.gustler.backend.routecatalog.domain.geometry;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * 정류장 마커와 별도의 원천 도로 형상을 읽는 경계다.
 */
public interface RouteGeometrySource {
    /**
     * 원천 노선 형상을 읽는다. 사용할 수신 결과가 없으면 빈 값이며 DB에 직접 발행하지 않는다.
     */
    Optional<Read> read(String sourceRouteId);
    record Read(RouteShape shape, OffsetDateTime receivedAt) {
    }
}
