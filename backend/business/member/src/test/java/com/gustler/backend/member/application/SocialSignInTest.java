package com.gustler.backend.member.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    private static final SocialIdentity KAKAO = new SocialIdentity(LoginProvider.KAKAO, "kakao-1", "연어팬");

    private final FakeMemberRepository repository = new FakeMemberRepository();

    @Test
    void 처음_인증한_계정은_제공자_닉네임으로_가입한다() {
        // given
        SocialSignIn signIn = signIn(0);

        // when
        SignedInMember actual = signIn.signIn(KAKAO);

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.registered()).isTrue();
            softly.assertThat(repository.nicknames()).containsExactly("연어팬");
            softly.assertThat(repository.identityCount()).isEqualTo(1);
        });
    }

    @Test
    void 제공자_닉네임이_없으면_연어_숫자_여섯_자리로_가입한다() {
        // given
        SocialSignIn signIn = signIn(482913);

        // when
        signIn.signIn(new SocialIdentity(LoginProvider.KAKAO, "kakao-1", null));

        // then
        assertThat(repository.nicknames()).containsExactly("연어482913");
    }

    @Test
    void 제공자_닉네임이_규칙에_맞지_않으면_자동_생성한다() {
        // given
        SocialSignIn signIn = signIn(482913);

        // when
        signIn.signIn(new SocialIdentity(LoginProvider.KAKAO, "kakao-1", "!!"));

        // then
        assertThat(repository.nicknames()).containsExactly("연어482913");
    }

    @Test
    void 애플_계정은_닉네임이_없으므로_자동_생성한다() {
        // given
        SocialSignIn signIn = signIn(7);

        // when
        signIn.signIn(new SocialIdentity(LoginProvider.APPLE, "apple-1", null));

        // then
        assertThat(repository.nicknames()).containsExactly("연어000007");
    }

    @Test
    void 제공자_닉네임이_이미_쓰이면_자동_생성한다() {
        // given
        SocialSignIn signIn = signIn(11);
        signIn.signIn(new SocialIdentity(LoginProvider.KAKAO, "kakao-0", "연어팬"));

        // when
        signIn.signIn(KAKAO);

        // then
        assertThat(repository.nicknames()).containsExactlyInAnyOrder("연어팬", "연어000011");
    }

    @Test
    void 대소문자만_다른_제공자_닉네임도_이미_쓰인_것으로_본다() {
        // given
        SocialSignIn signIn = signIn(5);
        signIn.signIn(new SocialIdentity(LoginProvider.KAKAO, "kakao-0", "Salmon"));

        // when
        signIn.signIn(new SocialIdentity(LoginProvider.KAKAO, "kakao-1", "salmon"));

        // then
        assertThat(repository.nicknames()).containsExactlyInAnyOrder("Salmon", "연어000005");
    }

    @Test
    void 여섯_자리를_다섯_번_모두_겹치면_여덟_자리로_만든다() {
        // given
        SocialSignIn signIn = signIn(3);
        signIn.signIn(new SocialIdentity(LoginProvider.APPLE, "apple-0", null));

        // when
        signIn.signIn(new SocialIdentity(LoginProvider.APPLE, "apple-1", null));

        // then
        assertSoftly(softly -> {
            softly.assertThat(repository.nicknames()).containsExactlyInAnyOrder("연어000003", "연어00000003");
            softly.assertThat(repository.registerCalls()).as("첫 가입 1번 + 여섯 자리 5번 + 여덟 자리 1번").isEqualTo(7);
        });
    }

    @Test
    void 자동_생성이_모두_겹치면_가입하지_못한다() {
        // given
        repository.rejectEveryNickname();
        SocialSignIn signIn = signIn(1);

        // when & then
        assertThatThrownBy(() -> signIn.signIn(new SocialIdentity(LoginProvider.APPLE, "apple-1", null)))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 이미_가입한_계정은_같은_회원으로_로그인하고_닉네임을_덮어쓰지_않는다() {
        // given
        SocialSignIn signIn = signIn(0);
        SignedInMember first = signIn.signIn(KAKAO);

        // when
        SignedInMember actual = signIn.signIn(new SocialIdentity(LoginProvider.KAKAO, "kakao-1", "바뀐이름"));

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.publicId()).isEqualTo(first.publicId());
            softly.assertThat(actual.registered()).isFalse();
            softly.assertThat(repository.nicknames()).containsExactly("연어팬");
            softly.assertThat(repository.loginRecords()).isEqualTo(1);
        });
    }

    @Test
    void 같은_계정이_동시에_먼저_가입했으면_그_회원으로_로그인한다() {
        // given
        SocialSignIn signIn = signIn(0);
        repository.raceOnNextRegister(KAKAO);

        // when
        SignedInMember actual = signIn.signIn(KAKAO);

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.registered()).isFalse();
            softly.assertThat(repository.identityCount()).isEqualTo(1);
            softly.assertThat(repository.loginRecords()).isEqualTo(1);
        });
    }

    private SocialSignIn signIn(int digits) {
        return new SocialSignIn(repository, CLOCK, new FixedRandom(digits));
    }

    private static final class FixedRandom implements RandomGenerator {
        private final int value;

        FixedRandom(int value) {
            this.value = value;
        }

        @Override
        public int nextInt(int bound) {
            return value;
        }

        @Override
        public long nextLong() {
            return value;
        }
    }

    private static final class FakeMemberRepository implements MemberRepository {
        private final Map<String, Member> byIdentity = new HashMap<>();
        private final List<Member> members = new ArrayList<>();
        private boolean rejectEveryNickname;
        private SocialIdentity racingIdentity;
        private int loginRecords;
        private int registerCalls;

        void rejectEveryNickname() {
            rejectEveryNickname = true;
        }

        void raceOnNextRegister(SocialIdentity identity) {
            racingIdentity = identity;
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
            if (racingIdentity != null) {
                racingIdentity = null;
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
        public void recordLogin(long memberId, OffsetDateTime at) {
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
