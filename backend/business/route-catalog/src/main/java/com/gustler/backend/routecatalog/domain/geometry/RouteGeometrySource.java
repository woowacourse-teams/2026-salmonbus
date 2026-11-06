package com.gustler.backend.routecatalog.domain.geometry;

import com.gustler.backend.routegeometry.api.RouteShapeEvidence;

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

    default Capture capture(String sourceRouteId) {
        return new Capture(read(sourceRouteId), java.util.List.of());
    }
    record Capture(Optional<Read> read, java.util.List<com.gustler.backend.routegeometry.api.RouteSourceReceipt> receipts) {
        public Capture { receipts=java.util.List.copyOf(receipts); }
    }
    record Read(RouteShape shape, OffsetDateTime receivedAt, RouteShapeEvidence evidence, String acquisitionManifestId) {
        public Read(RouteShape shape,OffsetDateTime receivedAt,RouteShapeEvidence evidence) {
            this(shape,receivedAt,evidence,null);
        }
        public Read(RouteShape shape, OffsetDateTime receivedAt) {
            this(shape, receivedAt, RouteShapeEvidence.EMPTY);
        }
    }
}
