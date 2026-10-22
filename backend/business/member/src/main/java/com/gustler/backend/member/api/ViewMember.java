package com.gustler.backend.member.api;

import java.util.Optional;
import java.util.UUID;

public interface ViewMember {
    /** 탈퇴했거나 없는 회원이면 비어 있다. 토큰은 유효한데 회원이 이미 사라진 경우를 호출자가 걸러낼 수 있다. */
    Optional<MemberProfile> view(UUID publicId);
}
