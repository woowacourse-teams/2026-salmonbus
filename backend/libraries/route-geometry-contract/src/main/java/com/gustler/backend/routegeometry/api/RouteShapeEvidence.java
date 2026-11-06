package com.gustler.backend.routegeometry.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** 획득 실패도 보존하는 보조 근거. 웹과 인증 API는 같은 기관의 서로 다른 전송 경로다. */
public record RouteShapeEvidence(
        String selectedSource, List<Source> sources, List<TypedPoint> webPoints) {
    public static final RouteShapeEvidence EMPTY =
            new RouteShapeEvidence("GBIS_API", List.of(), List.of());

    public RouteShapeEvidence {
        if (selectedSource == null || selectedSource.isBlank())
            throw new IllegalArgumentException("SOURCE_REQUIRED");
        sources = List.copyOf(sources);
        webPoints = List.copyOf(webPoints);
        if (sources.size() > 16
                || webPoints.size() > 100000
                || sources.stream().map(Source::id).distinct().count() != sources.size())
            throw new IllegalArgumentException("EVIDENCE_TOO_LARGE");
    }

    public record Source(String id, String status, String digest, java.time.Instant validUntil) {
        public Source(String id, String status, String digest) {
            this(id, status, digest, null);
        }

        public Source {
            if (id == null
                    || id.isBlank()
                    || status == null
                    || status.isBlank()
                    || digest != null && !digest.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("SOURCE_EVIDENCE_INVALID");
        }
    }

    public record TypedPoint(
            RouteDisplayInput.Point point, int type, String direction, boolean nodeConfirmed) {
        public TypedPoint {
            if (point == null
                    || type < 1
                    || type > 3
                    || !java.util.Set.of("UP", "DOWN").contains(direction))
                throw new IllegalArgumentException("TYPED_POINT_INVALID");
        }
    }

    /** worker 정지 중에도 만료된 공통 캐시가 보정 근거로 남지 않도록 조회 시 재평가한다. */
    public RouteShapeEvidence at(java.time.Instant now) {
        boolean nodeAvailable =
                sources.stream()
                        .anyMatch(
                                source ->
                                        source.id().equals("GG_BMS_NODES")
                                                && (source.status().equals("AVAILABLE") || source.status().equals("AVAILABLE_WITH_QUARANTINE"))
                                                && source.validUntil() != null
                                                && now.isBefore(source.validUntil()));
        var current =
                sources.stream()
                        .map(
                                source ->
                                        source.id().equals("GG_BMS_NODES")
                                                        && (source.status().equals("AVAILABLE") || source.status().equals("AVAILABLE_WITH_QUARANTINE"))
                                                        && !nodeAvailable
                                                ? new Source(
                                                        source.id(),
                                                        "STALE",
                                                        source.digest(),
                                                        source.validUntil())
                                                : source)
                        .toList();
        var points =
                nodeAvailable
                        ? webPoints
                        : webPoints.stream()
                                .map(
                                        point ->
                                                new TypedPoint(
                                                        point.point(),
                                                        point.type(),
                                                        point.direction(),
                                                        false))
                                .toList();
        return new RouteShapeEvidence(selectedSource, current, points);
    }

    public String revision() {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            (selectedSource
                                                            + sources.toString()
                                                            + webPoints.toString())
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
