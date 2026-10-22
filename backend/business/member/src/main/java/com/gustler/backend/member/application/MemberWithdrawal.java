package com.gustler.backend.member.application;

import com.gustler.backend.member.api.MemberIdentity;
import com.gustler.backend.member.api.PendingUnlink;
import com.gustler.backend.member.api.ProviderUnlink;
import com.gustler.backend.member.api.WithdrawMember;
import com.gustler.backend.member.api.WithdrawnMember;
import com.gustler.backend.member.domain.MemberRepository;
import com.gustler.backend.member.domain.ProviderUnlinkPolicy;
import com.gustler.backend.member.domain.ProviderUnlinkRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원을 지우고 제공자 연결 해제 요청을 같은 트랜잭션에 기록한다.
 *
 * <p>회원 삭제를 먼저 해 이긴 호출만 요청을 기록한다. 대기 요청은 임대 시간만큼 뒤를 다음 시도 시각으로 두어, 호출자가
 * 곧바로 시도하는 동안 주기 작업이 같은 요청을 집어 가지 않게 한다.
 */
@Component
@Transactional
public class MemberWithdrawal implements WithdrawMember {
    private final MemberRepository memberRepository;
    private final ProviderUnlinkRepository unlinkRepository;
    private final Clock clock;

    public MemberWithdrawal(MemberRepository memberRepository, ProviderUnlinkRepository unlinkRepository, Clock clock) {
        this.memberRepository = memberRepository;
        this.unlinkRepository = unlinkRepository;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MemberIdentity> identityOf(UUID publicId) {
        return memberRepository.findIdentity(publicId);
    }

    @Override
    public Optional<WithdrawnMember> withdraw(UUID publicId, ProviderUnlink unlink) {
        Objects.requireNonNull(unlink, "연결 해제 상태가 없다");
        return memberRepository.delete(publicId).map(identity -> withdrawn(identity, unlink));
    }

    private WithdrawnMember withdrawn(MemberIdentity identity, ProviderUnlink unlink) {
        return switch (unlink) {
            case PENDING -> new WithdrawnMember(identity, Optional.of(pending(identity)));
            case DONE -> new WithdrawnMember(identity, Optional.empty());
        };
    }

    private PendingUnlink pending(MemberIdentity identity) {
        OffsetDateTime now = now();
        final long id = unlinkRepository.savePending(identity, now.plus(ProviderUnlinkPolicy.LEASE), now);
        return new PendingUnlink(id, identity, 0);
    }

    /** PostgreSQL 의 {@code timestamptz} 정밀도에 맞춰 반환값과 저장값이 같게 한다. */
    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
