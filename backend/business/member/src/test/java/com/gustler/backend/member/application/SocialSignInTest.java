package com.gustler.backend.member.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.member.api.LoginProvider;
import com.gustler.backend.member.api.MemberIdentity;
import com.gustler.backend.member.api.MemberProfile;
import com.gustler.backend.member.api.SignedInMember;
import com.gustler.backend.member.api.SocialIdentity;
import com.gustler.backend.member.domain.Member;
import com.gustler.backend.member.domain.MemberRepository;
import com.gustler.backend.member.domain.NewMember;
import com.gustler.backend.member.domain.Nickname;
import com.gustler.backend.member.domain.NicknameUpdate;
import com.gustler.backend.member.domain.RegistrationResult;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class SocialSignInTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"), ZoneOffset.UTC);
    private static final SocialIdentity KAKAO = new SocialIdentity(LoginProvider.KAKAO, "kakao-1");

    private final FakeMemberRepository repository = new FakeMemberRepository();
    private final SocialSignIn signIn = new SocialSignIn(repository, CLOCK, new FirstChoiceRandom());

    @Test
    void 처음_인증한_계정은_형용사와_정류장과_숫자_두_자리로_만든_닉네임으로_가입한다() {
        // when
        SignedInMember actual = signIn.signIn(KAKAO);

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.registered()).isTrue();
            softly.assertThat(repository.nicknames()).containsExactly("졸린판교역10");
            softly.assertThat(repository.identityCount()).isEqualTo(1);
        });
    }

    @Test
    void 애플_계정도_같은_규칙으로_가입한다() {
        // when
        signIn.signIn(new SocialIdentity(LoginProvider.APPLE, "apple-1"));

        // then
        assertThat(repository.nicknames()).containsExactly("졸린판교역10");
    }

    @Test
    void 두_자리를_다섯_번_모두_겹치면_세_자리로_만든다() {
        // given
        repository.occupy("졸린판교역10");

        // when
        signIn.signIn(KAKAO);

        // then
        assertSoftly(softly -> {
            softly.assertThat(repository.nicknames()).containsExactly("졸린판교역10", "졸린판교역100");
            softly.assertThat(repository.registerCalls()).as("두 자리 5번 + 세 자리 1번").isEqualTo(6);
        });
    }

    @Test
    void 세_자리도_다섯_번_모두_겹치면_연어와_숫자_여덟_자리로_만든다() {
        // given
        repository.occupy("졸린판교역10");
        repository.occupy("졸린판교역100");

        // when
        signIn.signIn(KAKAO);

        // then
        assertSoftly(softly -> {
            softly.assertThat(repository.nicknames()).contains("연어10000000");
            softly.assertThat(repository.registerCalls()).as("두 자리 5번 + 세 자리 5번 + 여덟 자리 1번").isEqualTo(11);
        });
    }

    @Test
    void 연어_여덟_자리까지_모두_겹치면_가입하지_못한다() {
        // given
        repository.rejectEveryNickname();

        // when & then
        assertSoftly(softly -> {
            softly.assertThatThrownBy(() -> signIn.signIn(KAKAO)).isInstanceOf(IllegalStateException.class);
            softly.assertThat(repository.registerCalls()).as("세 단계 각 5번").isEqualTo(15);
        });
    }

    @Test
    void 이미_가입한_계정은_같은_회원으로_로그인하고_닉네임을_바꾸지_않는다() {
        // given
        SignedInMember first = signIn.signIn(KAKAO);

        // when
        SignedInMember actual = signIn.signIn(KAKAO);

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.publicId()).isEqualTo(first.publicId());
            softly.assertThat(actual.registered()).isFalse();
            softly.assertThat(repository.nicknames()).containsExactly("졸린판교역10");
            softly.assertThat(repository.registerCalls()).isEqualTo(1);
            softly.assertThat(repository.loginRecords()).isEqualTo(1);
        });
    }

    @Test
    void 같은_계정이_동시에_먼저_가입했으면_그_회원으로_로그인한다() {
        // given
        repository.raceOnNextRegister();

        // when
        SignedInMember actual = signIn.signIn(KAKAO);

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.registered()).isFalse();
            softly.assertThat(repository.identityCount()).isEqualTo(1);
            softly.assertThat(repository.loginRecords()).isEqualTo(1);
        });
    }

    /** 늘 범위의 맨 앞을 고르는 난수. 같은 가입 시도는 같은 닉네임을 만든다. */
    private static final class FirstChoiceRandom implements RandomGenerator {
        @Override
        public int nextInt(final int bound) {
            return 0;
        }

        @Override
        public long nextLong() {
            return 0;
        }
    }

    private static final class FakeMemberRepository implements MemberRepository {
        private final Map<String, Member> byIdentity = new HashMap<>();
        private final List<Member> members = new ArrayList<>();
        private boolean rejectEveryNickname;
        private boolean raceOnNextRegister;
        private int loginRecords;
        private int registerCalls;

        void rejectEveryNickname() {
            rejectEveryNickname = true;
        }

        void raceOnNextRegister() {
            raceOnNextRegister = true;
        }

        void occupy(String nickname) {
            add(new NewMember(UUID.randomUUID(), new Nickname(nickname), LoginProvider.KAKAO, "occupant-" + nickname,
                OffsetDateTime.now(CLOCK)));
        }

        List<String> nicknames() {
            return members.stream().map(Member::nickname).toList();
        }

        int identityCount() {
            return byIdentity.size();
        }

        int registerCalls() {
            return registerCalls;
        }

        int loginRecords() {
            return loginRecords;
        }

        @Override
        public Optional<Member> findByIdentity(LoginProvider provider, String subject) {
            return Optional.ofNullable(byIdentity.get(key(provider, subject)));
        }

        @Override
        public Optional<MemberProfile> findProfile(UUID publicId) {
            return Optional.empty();
        }

        @Override
        public RegistrationResult register(NewMember newMember) {
            registerCalls++;
            if (raceOnNextRegister) {
                raceOnNextRegister = false;
                add(new NewMember(UUID.randomUUID(), new Nickname("먼저가입"), newMember.provider(),
                    newMember.subject(), newMember.joinedAt()));
                return new RegistrationResult.AlreadyRegistered();
            }
            if (rejectEveryNickname || isTaken(newMember.nickname())) {
                return new RegistrationResult.NicknameTaken();
            }
            return new RegistrationResult.Registered(add(newMember));
        }

        @Override
        public NicknameUpdate changeNickname(UUID publicId, Nickname nickname) {
            return NicknameUpdate.MEMBER_NOT_FOUND;
        }

        @Override
        public void recordLogin(final long memberId, OffsetDateTime at) {
            loginRecords++;
        }

        @Override
        public Optional<MemberIdentity> findIdentity(UUID publicId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<MemberIdentity> delete(UUID publicId) {
            throw new UnsupportedOperationException();
        }

        private boolean isTaken(Nickname nickname) {
            return members.stream()
                .anyMatch(member -> member.nickname().toLowerCase(Locale.ROOT)
                    .equals(nickname.value().toLowerCase(Locale.ROOT)));
        }

        private Member add(NewMember newMember) {
            Member member = new Member(members.size() + 1L, newMember.publicId(), newMember.nickname().value());
            members.add(member);
            byIdentity.put(key(newMember.provider(), newMember.subject()), member);
            return member;
        }

        private static String key(LoginProvider provider, String subject) {
            return provider + ":" + subject;
        }
    }
}
