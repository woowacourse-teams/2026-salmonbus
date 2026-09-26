package com.gustler.backend.forecasting.domain.quality;

/** 노선 자료의 사용 조건이 달라질 때 파생 결과의 기준 버전을 변경한다. */
public final class RouteDataQuality {
    private final long routeId;
    private long revision;

    public RouteDataQuality(final long routeId, final long revision) {
        if (routeId <= 0 || revision <= 0) {
            throw new IllegalArgumentException("노선 ID와 품질 버전은 양수여야 한다");
        }
        this.routeId = routeId;
        this.revision = revision;
    }

    public void changeEligibility() { revision = Math.incrementExact(revision); }
    public long routeId() { return routeId; }
    public long revision() { return revision; }
}
