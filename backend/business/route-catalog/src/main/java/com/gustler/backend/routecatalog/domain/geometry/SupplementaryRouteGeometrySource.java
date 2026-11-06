package com.gustler.backend.routecatalog.domain.geometry;

import com.gustler.backend.routegeometry.api.RouteDisplayInput.Point;
import com.gustler.backend.routegeometry.api.RouteShapeEvidence;

import java.util.List;
import java.util.Optional;

/** 별도 전송 경로의 형상 또는 공통 도로 구조 캐시. 한 공급원의 실패가 다른 읽기를 막지 않는다. */
public interface SupplementaryRouteGeometrySource {
    String id();

    Contribution read(String routeId);

    record Contribution(
            Optional<RouteGeometrySource.Read> shape,
            RouteShapeEvidence.Source source,
            List<RouteShapeEvidence.TypedPoint> webPoints,
            List<Point> nodes,
            List<com.gustler.backend.routegeometry.api.RouteSourceReceipt> receipts,
            List<String> reusedManifestIds) {
        public Contribution(Optional<RouteGeometrySource.Read> shape,RouteShapeEvidence.Source source,
                List<RouteShapeEvidence.TypedPoint> webPoints,List<Point> nodes) {
            this(shape,source,webPoints,nodes,List.of(),List.of());
        }
        public Contribution {
            receipts = List.copyOf(receipts); reusedManifestIds = List.copyOf(reusedManifestIds);
            webPoints = List.copyOf(webPoints);
            nodes = List.copyOf(nodes);
        }

        public static Contribution unavailable(String id) {
            return new Contribution(
                    Optional.empty(),
                    new RouteShapeEvidence.Source(id, "UNAVAILABLE", null),
                    List.of(),
                    List.of());
        }
    }
}
