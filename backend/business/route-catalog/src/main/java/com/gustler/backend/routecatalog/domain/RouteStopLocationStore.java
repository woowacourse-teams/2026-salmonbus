package com.gustler.backend.routecatalog.domain;

import java.time.OffsetDateTime;

/**
 * 판본별 정류장 좌표 관측을 기록한다.
 * 누락·첫 수신·변경을 구분해 기존 좌표 이력을 보존한다.
 */
public interface RouteStopLocationStore {
    /**
     * 지정 판본과 관측 시각에 확인한 정류장 좌표를 저장한다. 다른 판본의 위치를 갱신하지 않는다.
     */
    Changes record(long versionId, RouteStops stops, OffsetDateTime observedAt);

    record Changes(int firstCaptured, int changed, int missing) {
        public boolean hasWrites() {
            return firstCaptured + changed > 0;
        }
    }
}
