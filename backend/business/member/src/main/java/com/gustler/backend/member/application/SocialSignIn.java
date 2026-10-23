package com.gustler.backend.member.application;

import com.gustler.backend.member.api.SignInWithSocialAccount;
import com.gustler.backend.member.api.SignedInMember;
import com.gustler.backend.member.api.SocialIdentity;
import com.gustler.backend.member.api.MemberRegistrationEvents;
import com.gustler.backend.member.api.MemberRegistrationEvents.Failure;
import com.gustler.backend.member.domain.Member;
import com.gustler.backend.member.domain.MemberRepository;
import com.gustler.backend.member.domain.NewMember;
import com.gustler.backend.member.domain.Nickname;
import com.gustler.backend.member.domain.NicknameGenerator;
import com.gustler.backend.member.domain.RegistrationResult;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 제공자 계정으로 회원을 찾고, 없으면 가입시킨다.
 *
 * <p>가입할 때 닉네임은 형용사·정류장 이름·숫자로 무작위로 만든다. 숫자 2자리, 숫자 3자리, {@code 연어} 와 숫자 8자리 순으로
 * 각각 정해진 횟수만큼 시도하고, 모두 겹치면 가입하지 못한다. 이후 로그인에서는 닉네임을 바꾸지 않는다.
 *
 * <p>중복 판정은 저장소의 유일 제약에 맡기므로 동시에 같은 닉네임으로 가입해도 한 명만 그 닉네임을 얻는다.
 */
@Component
public class SocialSignIn implements SignInWithSocialAccount {
    private static final int SHORT_DIGITS = 2;
    private static final int LONG_DIGITS = 3;
    private static final int SALMON_DIGITS = 8;
    private static final int ATTEMPTS_PER_STEP = 5;

    private final MemberRepository memberRepository;
    private final Clock clock;
    private final NicknameGenerator nicknames;
    private final MemberRegistrationEvents registrations;

    @Autowired
    public SocialSignIn(
        MemberRepository memberRepository,
        Clock clock,
        Optional<MemberRegistrationEvents> registrations
    ) {
        this(memberRepository, clock, new SecureRandom(), registrations.orElse(MemberRegistrationEvents.NONE));
    }

    SocialSignIn(MemberRepository memberRepository, Clock clock, RandomGenerator random) {
        this(memberRepository, clock, random, MemberRegistrationEvents.NONE);
    }

    SocialSignIn(
        MemberRepository memberRepository,
        Clock clock,
        RandomGenerator random,
        MemberRegistrationEvents registrations
    ) {
        this.memberRepository = memberRepository;
        this.clock = clock;
        this.nicknames = new NicknameGenerator(random);
        this.registrations = registrations;
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
        Optional<SignedInMember> registered = registerWith(identity, () -> nicknames.withStop(SHORT_DIGITS))
            .or(() -> registerWith(identity, () -> nicknames.withStop(LONG_DIGITS)))
            .or(() -> registerWith(identity, () -> nicknames.salmonOnly(SALMON_DIGITS)));
        if (registered.isPresent()) {
            return registered.get();
        }
        registrations.failed(identity.provider(), Failure.NICKNAME_EXHAUSTED);
        throw new IllegalStateException("자동 생성 닉네임이 계속 겹쳐 가입하지 못했다");
    }

    private Optional<SignedInMember> registerWith(SocialIdentity identity, Supplier<Nickname> nextNickname) {
        for (int attempt = 0; attempt < ATTEMPTS_PER_STEP; attempt++) {
            Optional<SignedInMember> registered = tryRegister(identity, nextNickname.get());
            if (registered.isPresent()) {
                return registered;
            }
        }
        return Optional.empty();
    }

    private Optional<SignedInMember> tryRegister(SocialIdentity identity, Nickname nickname) {
        NewMember newMember = new NewMember(
            UUID.randomUUID(), nickname, identity.provider(), identity.subject(), OffsetDateTime.now(clock));
        RegistrationResult result;
        try {
            result = memberRepository.register(newMember);
        } catch (RuntimeException exception) {
            registrations.failed(identity.provider(), Failure.REPOSITORY);
            throw exception;
        }
        return switch (result) {
            case RegistrationResult.Registered registered -> {
                registrations.succeeded(identity.provider());
                yield Optional.of(new SignedInMember(registered.member().publicId(), true));
            }
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
