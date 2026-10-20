package com.gustler.backend.quota.api;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

/** 한국 날짜별 도착정보 호출 예산을 예약하고 사용할 수 없는 키를 제외하는 경계다. */
public interface ArrivalCallQuota {
    ZoneId KOREA = ZoneId.of("Asia/Seoul");

    /**
     * 전체와 키별 예산을 함께 예약한다. 사용할 수 있는 예산이 없으면 빈 값을 반환한다.
     * 확정한 예약은 전송 실패 또는 전송 여부가 불명확한 경우에도 환급하지 않는다.
     */
    Optional<Reservation> reserve(Instant dispatchAt);

    /** 예약한 한국 날짜에 해당 키의 남은 예산을 소진시켜 이후 선택에서 제외한다. */
    void exclude(Reservation reservation);

    /** 전송할 키와 예약 예산이 속한 한국 날짜다. */
    record Reservation(String keyAlias, LocalDate koreanDate) {
        public boolean covers(Instant now) {
            return koreanDate.equals(now.atZone(KOREA).toLocalDate());
        }
    }
}
