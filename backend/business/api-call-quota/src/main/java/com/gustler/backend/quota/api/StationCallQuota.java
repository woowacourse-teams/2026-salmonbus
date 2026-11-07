package com.gustler.backend.quota.api;

import java.time.Instant;

/**
 * 주변 정류소 조회의 키별 한국 날짜 예산. 확정한 예약은 전송 실패에도 환급하지 않는다.
 */
public interface StationCallQuota {

    boolean reserve(String keyAlias, Instant dispatchAt, int dailyLimit);

    void blockForDay(String keyAlias, Instant dispatchAt);
}
