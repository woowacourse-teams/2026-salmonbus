package com.gustler.backend.collector;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 호출을 보내기 전에 하루 한도에서 자리를 잡는다.
 *
 * <p>하루 10,000 회인데 두 노선 90초 수집 실측이 8,194 회다. 여유가 82퍼센트까지 차 있어서
 * 보내고 나서 세면 늦는다.
 *
 * <p>자리를 잡은 뒤에는 되돌리지 않는다. 부른 쪽 트랜잭션이 되돌아가도, 프로세스가 죽어도 그대로 둔다.
 * 안 보냈을 수도 있는 호출을 안 썼다고 되돌리면 다음 시도가 같은 자리를 또 쓴다.
 */
@Component
public class CallQuotaLedger {

    /** 한도는 한국 자정에 리셋된다. 서버 타임존이나 UTC 날짜와 다르다. */
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

    private final CallQuotaRepository callQuotaRepository;
    private final int dailyLimit;

    public CallQuotaLedger(
        CallQuotaRepository callQuotaRepository,
        GbisProperties properties
    ) {
        this.callQuotaRepository = callQuotaRepository;
        this.dailyLimit = properties.dailyLimit();
    }

    /**
     * 제 트랜잭션에서 돈다. 부른 쪽이 되돌아가도 쓴 횟수는 남는다.
     * 같은 트랜잭션에서 돌면 수집이 실패할 때 예약까지 같이 사라져 보수적 소비가 깨진다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean reserve(
        CallQuota quota,
        OffsetDateTime requestedAt
    ) {
        return callQuotaRepository.reserveOne(quota, koreanDateOf(requestedAt), dailyLimit);
    }

    private LocalDate koreanDateOf(
        OffsetDateTime requestedAt
    ) {
        return requestedAt.atZoneSameInstant(KOREA).toLocalDate();
    }
}
