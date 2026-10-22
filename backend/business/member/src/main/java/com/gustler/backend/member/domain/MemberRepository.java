package com.gustler.backend.member.domain;

import com.gustler.backend.member.api.LoginProvider;
import com.gustler.backend.member.api.MemberIdentity;
import com.gustler.backend.member.api.MemberProfile;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

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

    /** 닉네임 고유성 위반도 예외 없이 {@link NicknameUpdate#TAKEN} 으로 알린다. */
    NicknameUpdate changeNickname(UUID publicId, Nickname nickname);

    Optional<MemberIdentity> findIdentity(UUID publicId);

    /**
     * 회원을 지우고 그 회원의 제공자 계정을 돌려준다. 제공자 계정과 리프레시 토큰은 FK CASCADE 로 함께 지워진다.
     *
     * <p>같은 회원을 동시에 지우면 한 호출만 값을 얻고 나머지는 비어 있다. 없는 회원이면 비어 있다.
     */
    Optional<MemberIdentity> delete(UUID publicId);
}
