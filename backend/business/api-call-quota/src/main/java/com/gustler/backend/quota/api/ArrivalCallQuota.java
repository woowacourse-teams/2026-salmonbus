package com.gustler.backend.quota.api;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

public interface ArrivalCallQuota {
    ZoneId KOREA = ZoneId.of("Asia/Seoul");

    /** 전체와 키별 예산을 함께 예약한다. 확정한 예약은 전송 실패/불명에도 환급하지 않는다. */
    Optional<Reservation> reserve(Instant dispatchAt);

    void exclude(Reservation reservation);

    record Reservation(String keyAlias, LocalDate koreanDate) {
        public boolean covers(Instant now) { return koreanDate.equals(now.atZone(KOREA).toLocalDate()); }
    }
}
