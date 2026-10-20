package com.gustler.backend.arrivals.api;

import java.time.Instant;

/**
 * 도착정보 수신 자료의 보존 기간 정리를 실행하는 업무 경계다.
 */
public interface RetainArrivalSnapshots {
    /**
     * 기준 시각보다 오래된 도착정보를 최대 limit건 정리하고 삭제 건수를 반환한다.
     */
    int purgeExpiredBefore(Instant cutoff, int limit);
}
