package com.gustler.backend.member.domain;

import com.gustler.backend.member.api.MemberIdentity;
import com.gustler.backend.member.api.PendingUnlink;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** 탈퇴한 회원의 제공자 연결 해제 요청 저장소. 요청마다 한 행이다. */
public interface ProviderUnlinkRepository {
    /** 실패 횟수 0 인 대기 요청을 저장하고 ID 를 돌려준다. {@code leasedUntil} 까지는 주기 작업이 집어 가지 않는다. */
    long savePending(MemberIdentity identity, OffsetDateTime leasedUntil, OffsetDateTime now);

    /**
     * 다시 시도할 때가 된 대기 요청을 {@code limit} 개까지 한 문장으로 집어 {@code leasedUntil} 까지 임대한다.
     *
     * <p>여러 트랜잭션이 동시에 불러도 한 요청은 한 호출에만 나온다.
     */
    List<PendingUnlink> claimDue(OffsetDateTime now, OffsetDateTime leasedUntil, int limit);

    /** 대기 요청의 실패 횟수를 읽고 트랜잭션이 끝날 때까지 행을 잠근다. 없거나 포기한 요청이면 비어 있다. */
    Optional<Integer> lockPendingAttempts(long id);

    void recordFailure(
        long id,
        UnlinkFailure failure,
        String lastError,
        OffsetDateTime now
    );

    /** 요청을 지운다. 없으면 아무 일도 하지 않는다. */
    void delete(long id);

    /** {@code updatedBefore} 이전에 포기한 요청을 {@code limit} 개까지 지우고 지운 개수를 돌려준다. */
    int deleteGaveUp(OffsetDateTime updatedBefore, int limit);
}
