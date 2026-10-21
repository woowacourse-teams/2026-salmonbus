package com.gustler.backend.member.domain;

import com.gustler.backend.member.api.LoginProvider;
import com.gustler.backend.member.api.MemberProfile;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/** 회원 저장소. */
public interface MemberRepository {
    Optional<Member> findByIdentity(LoginProvider provider, String subject);

    Optional<MemberProfile> findProfile(UUID publicId);

    /**
     * 회원과 제공자 계정을 한 번에 저장한다.
     *
     * <p>닉네임이 겹치거나 같은 제공자 계정이 먼저 가입했어도 예외 없이 결과로 알린다.
     */
    RegistrationResult register(NewMember newMember);

    void recordLogin(long memberId, OffsetDateTime at);
}
