package com.gustler.backend.api.route.application;

import java.time.Instant;

/**
 * 지도 형상 기본 응답을 정하는 입력. 같으면 응답 본문과 ETag가 같다.
 *
 * <p>{@code sourceRevision}은 표시 선택 전 형상의 {@code RouteGeometry.revision()}이다. 노선 기본정보·정류장 방문과 좌표·형상 ID·수신
 * 시각·형상점을 덮는다. 그 판본이 담지 않는 형상 필드(입력 출처, 저장된 보조 근거)는 따로 담는다.
 */
public record RouteGeometryFingerprint(
        String routeId,
        String sourceRevision,
        String sourceManifestId,
        Instant evidenceEvaluatedAt,
        String unboundSourceManifestId,
        String storedEvidenceRevision,
        RouteDisplayFingerprint display) {
}
