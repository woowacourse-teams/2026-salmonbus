package com.gustler.backend.member.application;

import com.gustler.backend.member.api.SignInWithSocialAccount;
import com.gustler.backend.member.api.SignedInMember;
import com.gustler.backend.member.api.SocialIdentity;
import com.gustler.backend.member.domain.Member;
import com.gustler.backend.member.domain.MemberRepository;
import com.gustler.backend.member.domain.Nickname;
import com.gustler.backend.member.domain.NewMember;
import com.gustler.backend.member.domain.RegistrationResult;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.security.SecureRandom;
import java.util.random.RandomGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 제공자 계정으로 회원을 찾고, 없으면 가입시킨다.
 *
 * <p>가입할 때 닉네임은 제공자가 알려 준 닉네임, 자동 생성 6자리, 자동 생성 8자리 순으로 시도한다.
 * 제공자 닉네임은 가입 때 한 번만 쓰고 이후 로그인에서는 덮어쓰지 않는다.
 * 중복 판정은 저장소의 유일 제약에 맡기므로 동시에 같은 닉네임으로 가입해도 한 명만 그 닉네임을 얻는다.
 */
@Component
public class SocialSignIn implements SignInWithSocialAccount {
    private static final int SHORT_DIGITS = 6;
    private static final int LONG_DIGITS = 8;
    private static final int ATTEMPTS_PER_LENGTH = 5;

    private final MemberRepository memberRepository;
    private final Clock clock;
    private final RandomGenerator random;

    @Autowired
    public SocialSignIn(MemberRepository memberRepository, Clock clock) {
        this(memberRepository, clock, new SecureRandom());
    }

    SocialSignIn(MemberRepository memberRepository, Clock clock, RandomGenerator random) {
        this.memberRepository = memberRepository;
        this.clock = clock;
        this.random = random;
    }

    @Override
    public SignedInMember signIn(SocialIdentity identity) {
        Optional<Member> existing = memberRepository.findByIdentity(identity.provider(), identity.subject());
        if (existing.isPresent()) {
            return loggedIn(existing.get());
        }
        return register(identity);
    }

    private SignedInMember register(SocialIdentity identity) {
        Optional<SignedInMember> withSuggested = Nickname.of(identity.suggestedNickname())
            .flatMap(nickname -> tryRegister(identity, nickname));
        if (withSuggested.isPresent()) {
            return withSuggested.get();
        }
        return registerWithGenerated(identity, SHORT_DIGITS)
            .or(() -> registerWithGenerated(identity, LONG_DIGITS))
            .orElseThrow(() -> new IllegalStateException("자동 생성 닉네임이 계속 겹쳐 가입하지 못했다"));
    }

    private Optional<SignedInMember> registerWithGenerated(SocialIdentity identity, int digits) {
        for (int attempt = 0; attempt < ATTEMPTS_PER_LENGTH; attempt++) {
            Optional<SignedInMember> registered = tryRegister(identity, Nickname.generated(random, digits));
            if (registered.isPresent()) {
                return registered;
            }
        }
        return Optional.empty();
    }

    private Optional<SignedInMember> tryRegister(SocialIdentity identity, Nickname nickname) {
        NewMember newMember = new NewMember(
            UUID.randomUUID(), nickname, identity.provider(), identity.subject(), OffsetDateTime.now(clock));
        return switch (memberRepository.register(newMember)) {
            case RegistrationResult.Registered registered ->
                Optional.of(new SignedInMember(registered.member().publicId(), true));
            case RegistrationResult.NicknameTaken taken -> Optional.empty();
            case RegistrationResult.AlreadyRegistered already -> Optional.of(loggedIn(
                memberRepository.findByIdentity(identity.provider(), identity.subject())
                    .orElseThrow(() -> new IllegalStateException("먼저 가입한 회원을 찾지 못했다"))));
        };
    }

    private SignedInMember loggedIn(Member member) {
        memberRepository.recordLogin(member.id(), OffsetDateTime.now(clock));
        return new SignedInMember(member.publicId(), false);
    }
}
