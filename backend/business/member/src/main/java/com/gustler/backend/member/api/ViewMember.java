package com.gustler.backend.member.api;

import java.util.Optional;
import java.util.UUID;

/** 회원 정보를 조회한다. */
public interface ViewMember {
    /** 탈퇴했거나 없는 회원이면 비어 있다. 세션에 남은 회원이 이미 사라진 경우를 호출자가 걸러낼 수 있다. */
    Optional<MemberProfile> view(UUID publicId);
}
