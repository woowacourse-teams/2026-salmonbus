package com.gustler.backend.routecatalog.api;

import java.time.OffsetDateTime;
import java.util.Optional;

/** 수집에 사용할 현재 노선 버전. 없는 경우 기존 bootstrap 정책으로 노선을 읽어 등록한다. */
public interface CurrentRouteVersion {

    Optional<RouteReference> currentVersionOf(String sourceRouteId, OffsetDateTime scheduledAt);
}
