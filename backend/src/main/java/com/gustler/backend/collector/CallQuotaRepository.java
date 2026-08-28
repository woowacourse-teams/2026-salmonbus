package com.gustler.backend.collector;

import java.time.LocalDate;

public interface CallQuotaRepository {

    /**
     * 그날 장부에서 자리 하나를 잡는다. 잡으면 참, 자리가 없으면 거짓.
     *
     * <p>읽고 나서 쓰면 두 프로세스가 같은 잔여를 보고 둘 다 잡는다. 갱신 한 문장으로 끝내야 한다.
     */
    boolean reserveOne(
        CallQuota quota,
        LocalDate kstDate,
        int dailyLimit
    );
}
