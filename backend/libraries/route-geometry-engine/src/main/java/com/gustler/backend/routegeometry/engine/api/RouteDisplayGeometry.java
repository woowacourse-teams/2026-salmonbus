package com.gustler.backend.routegeometry.engine.api;

import com.gustler.backend.routegeometry.engine.api.RouteGeometry.Point;
import com.gustler.backend.routegeometry.api.ManualRouteDisplay.Connection;
import com.gustler.backend.routegeometry.api.RouteShapeEvidence;

import java.util.List;
import java.util.Map;

/** 현재 원본 입력에 귀속된 하나의 표시 형상. 원본 자료와 분리해 선택한다. */
public record RouteDisplayGeometry(
        RoutePaths paths,
        Map<Integer, Connection> connections,
        Map<Integer, Point> stationPositions,
        String revision,
        Source source,
        List<RouteShapeEvidence.Source> evidence,
        Quality quality,
        // 보정 표시 경로 paths와 구분되는 원본의 방향별 경로. worker가 미리 계산하며 없으면 null이다.
        RoutePaths sourcePaths,
        com.gustler.backend.routegeometry.engine.api.accuracy.RouteAccuracyVerifier.Snapshot accuracyProof) {
    public RouteDisplayGeometry(RoutePaths paths,Map<Integer,Connection> connections,Map<Integer,Point> stationPositions,
            String revision,Source source,List<RouteShapeEvidence.Source> evidence,Quality quality,RoutePaths sourcePaths) {
        this(paths,connections,stationPositions,revision,source,evidence,quality,sourcePaths,null);
    }
    public RouteDisplayGeometry withAccuracyProof(
            com.gustler.backend.routegeometry.engine.api.accuracy.RouteAccuracyVerifier.Snapshot proof) {
        return new RouteDisplayGeometry(paths,connections,stationPositions,revision,source,evidence,quality,sourcePaths,proof);
    }

    public RouteDisplayGeometry(
            RoutePaths paths,
            Map<Integer, Connection> connections,
            Map<Integer, Point> stationPositions,
            String revision,
            Source source,
            List<RouteShapeEvidence.Source> evidence,
            Quality quality) {
        this(paths, connections, stationPositions, revision, source, evidence, quality, null);
    }

    public RouteDisplayGeometry(
            RoutePaths paths,
            Map<Integer, Connection> connections,
            Map<Integer, Point> stationPositions,
            String revision,
            Source source,
            List<RouteShapeEvidence.Source> evidence) {
        this(paths, connections, stationPositions, revision, source, evidence, null);
    }

    public RouteDisplayGeometry(
            RoutePaths paths,
            Map<Integer, Connection> connections,
            Map<Integer, Point> stationPositions,
            String revision,
            Source source) {
        this(paths, connections, stationPositions, revision, source, List.of(), null);
    }

    public enum QualityStatus {
        PASSED,
        REVIEW_REQUIRED,
        SOURCE_ONLY,
        WITHHELD
    }

    public record QualityIssue(String code, String direction, Integer sequence, Double meters) {}

    public record Quality(QualityStatus status, List<QualityIssue> issues) {
        public Quality {
            issues = List.copyOf(issues);
        }
    }

    public enum Source {
        MANUAL_REVIEWED,
        AUTOMATIC_FALLBACK
    }

    public RouteDisplayGeometry {
        evidence = List.copyOf(evidence);
        connections = Map.copyOf(connections);
        stationPositions = Map.copyOf(stationPositions);
        if (revision == null || revision.isBlank() || source == null) {
            throw new IllegalArgumentException("표시 형상의 판본과 출처가 필요하다");
        }
    }
}
