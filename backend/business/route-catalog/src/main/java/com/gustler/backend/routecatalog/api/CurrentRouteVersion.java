package com.gustler.backend.routecatalog.api;

import java.util.Optional;

/** 준비된 노선 판본을 조회한다. 없는 판본을 이 호출에서 준비하지 않는다. */
public interface CurrentRouteVersion {
    Optional<RouteReference> findCurrentVersion(String sourceRouteId);
}
