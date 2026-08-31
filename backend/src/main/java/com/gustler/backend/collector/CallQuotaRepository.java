package com.gustler.backend.collector;

import java.time.LocalDate;

public interface CallQuotaRepository {

    /**
     * 그날 장부에서 자리를 원하는 만큼 잡는다. 다 잡으면 참, 그만큼 안 남았으면 거짓.
     *
     * <p>읽고 나서 쓰면 두 프로세스가 같은 잔여를 보고 둘 다 잡는다. 갱신 한 문장으로 끝내야 한다.
     * 부분으로 잡지 않는다. 호출 두 번이 필요한데 한 자리만 잡으면 두 번째가 한도를 몰래 넘는다.
     */
    boolean reserve(
        CallQuota quota,
        LocalDate kstDate,
        int calls,
        int dailyLimit
    );
}
