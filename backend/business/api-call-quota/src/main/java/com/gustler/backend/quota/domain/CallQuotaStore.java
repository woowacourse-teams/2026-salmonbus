package com.gustler.backend.quota.domain;

import com.gustler.backend.quota.api.CallQuota;
import java.time.LocalDate;
import java.util.Optional;

/** 서비스·한국 날짜·키별 호출 한도의 원자 갱신을 제공하는 저장 포트다. */
public interface CallQuotaStore {
    /**
     * 해당 날짜의 한도에서 요청한 호출 횟수를 예약한다. 전부 예약하면 참, 한도가 부족하면 거짓을 반환한다.
     *
     * <p>조회 후 갱신하면 두 프로세스가 같은 잔여 한도를 보고 중복 예약할 수 있다. 갱신 한 문장으로 처리해야 한다.
     * 일부만 예약하지 않는다. 호출 두 번이 필요한데 한 번만 예약하면 두 번째 호출이 한도를 초과한다.
     */
    boolean reserve(DailyCallQuota.Reservation reservation);

    /** 장부를 소진 처리하고 기존 예약 횟수를 반환한다. 해당 장부가 없으면 빈 값이다. */
    Optional<Integer> exclude(CallQuota service, LocalDate koreanDate, String keyAlias);
}
