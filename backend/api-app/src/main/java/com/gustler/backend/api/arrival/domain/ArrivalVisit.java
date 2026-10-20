package com.gustler.backend.api.arrival.domain;

import java.time.Instant;

/** 현재 노선 판본에서 이 정류장을 방문하는 한 지점. 왕복·회차 방문을 구분한다. */
public record ArrivalVisit(String routeId, String sourceRouteId, long referenceVersionId,
    int stopSequence, String direction, Instant validFrom) {
    public boolean matches(String sourceRoute, final int sourceOrder, Instant receivedAt) {
        return sourceRouteId.equals(sourceRoute) && stopSequence == sourceOrder
            && !receivedAt.isBefore(validFrom);
    }
}
