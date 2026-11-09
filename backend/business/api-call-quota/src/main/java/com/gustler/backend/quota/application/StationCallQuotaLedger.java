package com.gustler.backend.quota.application;

import com.gustler.backend.quota.api.CallQuota;
import com.gustler.backend.quota.api.StationCallQuota;
import com.gustler.backend.quota.domain.CallQuotaStore;
import com.gustler.backend.quota.domain.DailyCallQuota;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 정류소 수집을 기존 한국 날짜 규칙·원자 예약 저장소에 연결한다. 별도 예약 SQL을 두지 않는다.
 */
public class StationCallQuotaLedger implements StationCallQuota {

    private final CallQuotaStore store;

    public StationCallQuotaLedger(CallQuotaStore store) {
        this.store = store;
    }

    @Override
    @Transactional(readOnly = false, propagation = Propagation.REQUIRES_NEW)
    public boolean reserve(String alias, Instant dispatchAt, final int dailyLimit) {
        return DailyCallQuota.at(CallQuota.BUS_STATION, alias, dispatchAt.atOffset(ZoneOffset.UTC), dailyLimit)
            .reservationFor(1)
            .map(store::reserve)
            .orElse(false);
    }

    /**
     * 원천 전송 전에 예약한 해당 장부를 소진 처리한다.
     */
    @Override
    @Transactional(readOnly = false, propagation = Propagation.REQUIRES_NEW)
    public void blockForDay(String alias, Instant dispatchAt) {
        LocalDate date = dispatchAt.atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
        store.exclude(CallQuota.BUS_STATION, date, alias);
    }
}
