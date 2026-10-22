package com.gustler.backend.member.api;

import java.util.Optional;
import java.util.UUID;

public interface WithdrawMember {
    /** 회원의 제공자 계정. 탈퇴했거나 없는 회원이면 비어 있다. */
    Optional<MemberIdentity> identityOf(UUID publicId);

    /**
     * 회원을 지운다. 그 회원의 제공자 계정과 리프레시 토큰도 함께 지워져 모든 기기의 로그인이 끝난다.
     *
     * <p>회원 삭제와 연결 해제 기록은 한 트랜잭션이다. 같은 회원이 동시에 두 번 탈퇴해도 한 번만 지워지고 다른 쪽은 비어 있다.
     * 이미 없는 회원이면 비어 있다. {@code unlink} 가 {@code null} 이면 아무것도 지우지 않고 {@link NullPointerException} 이다.
     */
    Optional<WithdrawnMember> withdraw(UUID publicId, ProviderUnlink unlink);
}
