package com.gustler.backend.member.application;

import com.gustler.backend.member.api.PendingUnlink;
import com.gustler.backend.member.api.ProviderUnlinks;
import com.gustler.backend.member.domain.ProviderUnlinkPolicy;
import com.gustler.backend.member.domain.ProviderUnlinkRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 연결 해제 요청을 임대해 나눠 주고, 시도 결과를 받아 요청을 지우거나 늦춘다.
 *
 * <p>제공자 API 호출은 이 클래스의 트랜잭션 밖에서 호출자가 한다. 임대는 다음 시도 시각을 임대 시간만큼 뒤로 미는 것이라
 * 호출자가 결과를 알리지 못하고 죽어도 임대가 끝나면 다른 곳이 이어받는다. 실패 기록은 행을 잠그고 횟수를 읽은 뒤
 * 갱신하므로 같은 요청의 실패가 겹쳐도 횟수를 잃지 않는다.
 */
@Component
@Transactional
public class ProviderUnlinkRetry implements ProviderUnlinks {
    private final ProviderUnlinkRepository repository;
    private final Clock clock;

    public ProviderUnlinkRetry(ProviderUnlinkRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public List<PendingUnlink> claimDue(final int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("집을 요청 수는 1 이상이어야 한다");
        }
        OffsetDateTime now = now();
        return repository.claimDue(now, now.plus(ProviderUnlinkPolicy.LEASE), limit);
    }

    @Override
    public void succeeded(final long id) {
        repository.delete(id);
    }

    /** 대기 중이 아닌 요청이면 아무 일도 하지 않는다. */
    @Override
    public void failed(final long id, String reason) {
        OffsetDateTime now = now();
        repository.lockPendingAttempts(id).ifPresent(previousFailures -> repository.recordFailure(
            id, ProviderUnlinkPolicy.afterFailure(previousFailures, now), ProviderUnlinkPolicy.errorOf(reason), now));
    }

    @Override
    public int purgeGaveUp() {
        return repository.deleteGaveUp(now().minus(ProviderUnlinkPolicy.GAVE_UP_RETENTION), PURGE_BATCH_SIZE);
    }

    /** PostgreSQL 의 {@code timestamptz} 정밀도에 맞춰 반환값과 저장값이 같게 한다. */
    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
