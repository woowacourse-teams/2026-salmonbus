package com.gustler.backend.member.api;

import java.util.Optional;

/**
 * 탈퇴를 마친 회원.
 *
 * <p>{@code pendingUnlink} 는 연결 해제를 {@link ProviderUnlink#PENDING} 으로 요청했을 때만 있다. 그 요청은 이미 호출자 몫으로
 * 잡혀 있어 주기 작업이 같은 요청을 동시에 시도하지 않는다. 호출자는 한 번 시도한 뒤 결과를 {@link ProviderUnlinks} 에 알린다.
 */
public record WithdrawnMember(MemberIdentity identity, Optional<PendingUnlink> pendingUnlink) {
}
