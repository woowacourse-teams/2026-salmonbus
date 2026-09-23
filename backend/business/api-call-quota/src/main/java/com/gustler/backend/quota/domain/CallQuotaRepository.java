package com.gustler.backend.quota.domain;

public interface CallQuotaRepository {

    /**
     * 해당 날짜의 한도에서 요청한 호출 횟수를 예약한다. 전부 예약하면 참, 한도가 부족하면 거짓을 반환한다.
     *
     * <p>조회 후 갱신하면 두 프로세스가 같은 잔여 한도를 보고 중복 예약할 수 있다. 갱신 한 문장으로 처리해야 한다.
     * 일부만 예약하지 않는다. 호출 두 번이 필요한데 한 번만 예약하면 두 번째 호출이 한도를 초과한다.
     */
    boolean reserve(DailyCallQuota.Reservation reservation);
}
