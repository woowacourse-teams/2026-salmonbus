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
 * <p>트랜잭션 경계는 부르는 쪽이 정한다. 여기서 제 트랜잭션을 열면 자리는 잡혔는데 그 자리를 쓸
 * batch 가 안 열리는 틈이 생긴다. 그 사이에 batch INSERT 가 실패하거나 프로세스가 죽으면
 * 쓴 횟수만 오르고 아무 기록도 안 남는다. 그래서 ObservationBatchLedger 가 바깥에서 새 트랜잭션을
 * 열고 자리와 batch 를 같이 커밋한다.
 *
 * <p>그 커밋이 끝난 뒤로는 되돌리지 않는다. 프로세스가 죽어도 그대로 둔다.
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
     * 부른 쪽 트랜잭션에 합류한다. 트랜잭션 경계는 부르는 쪽이 정한다.
     *
     * <p>여기서 제 트랜잭션을 열면 자리는 잡혔는데 그 자리를 쓸 판이 안 열리는 틈이 생긴다.
     * 그 사이에 판 INSERT 가 실패하거나 프로세스가 죽으면 쓴 횟수만 오르고 아무 기록도 안 남는다.
     * 자리와 판이 같이 커밋되도록 ObservationBatchLedger 가 바깥에서 새 트랜잭션을 연다.
     */
    @Transactional
    public boolean reserve(
        CallQuota quota,
        OffsetDateTime requestedAt
    ) {
        return callQuotaRepository.reserveOne(quota, koreanDateOf(requestedAt), dailyLimit);
    }

    /**
     * 보내기 직전에도 그 날짜 자리를 들고 있나. 없으면 새로 잡는다.
     *
     * <p>자리를 잡은 시각과 실제로 보내는 시각 사이에 한국 자정이 지날 수 있다.
     * 23:59:59 에 잡고 00:00:00 에 보내면 상류는 다음 날 호출로 세는데 우리 장부는 전날에 달아둔 것이라,
     * 다음 날 자리가 하나 덜 닳고 그만큼 상류 한도를 넘긴다.
     */
    @Transactional
    public boolean holdsSeatAt(
        CallQuota quota,
        OffsetDateTime reservedAt,
        OffsetDateTime dispatchAt
    ) {
        if (koreanDateOf(reservedAt).equals(koreanDateOf(dispatchAt))) {
            return true;
        }
        return callQuotaRepository.reserveOne(quota, koreanDateOf(dispatchAt), dailyLimit);
    }

    private LocalDate koreanDateOf(
        OffsetDateTime requestedAt
    ) {
        return requestedAt.atZoneSameInstant(KOREA).toLocalDate();
    }
}
