package com.gustler.backend.member.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.member.api.LoginProvider;
import com.gustler.backend.member.api.SignInWithSocialAccount;
import com.gustler.backend.member.api.SignedInMember;
import com.gustler.backend.member.api.SocialIdentity;
import com.gustler.backend.member.domain.Member;
import com.gustler.backend.member.domain.MemberRepository;
import com.gustler.backend.member.domain.NewMember;
import com.gustler.backend.member.domain.Nickname;
import com.gustler.backend.member.domain.NicknameUpdate;
import com.gustler.backend.member.domain.RegistrationResult;
import com.gustler.backend.member.support.MemberIntegrationTest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@MemberIntegrationTest
class JdbcMemberRepositoryTest {
    private static final OffsetDateTime JOINED_AT = OffsetDateTime.parse("2026-10-21T09:00:00+09:00");
    private static final int CONCURRENT_REQUESTS = 8;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private SignInWithSocialAccount signIn;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void 회원을_비운다() {
        jdbcClient.sql("DELETE FROM member").update();
    }

    @Test
    void 가입하면_회원과_제공자_계정이_함께_저장된다() {
        // given
        NewMember newMember = newMember("연어팬", LoginProvider.KAKAO, "kakao-1");

        // when
        RegistrationResult actual = memberRepository.register(newMember);

        // then
        Member found = memberRepository.findByIdentity(LoginProvider.KAKAO, "kakao-1").orElseThrow();
        assertSoftly(softly -> {
            softly.assertThat(actual).isInstanceOf(RegistrationResult.Registered.class);
            softly.assertThat(found.publicId()).isEqualTo(newMember.publicId());
            softly.assertThat(found.nickname()).isEqualTo("연어팬");
            softly.assertThat(memberCount()).isEqualTo(1);
        });
    }

    @Test
    void 금칙어가_나중에_늘어도_이미_가입한_회원의_닉네임은_그대로_읽는다() {
        // given
        UUID publicId = UUID.randomUUID();
        long memberId = jdbcClient.sql("""
            INSERT INTO member (public_id, nickname, created_at, last_login_at)
            VALUES (?, '관리자1', now(), now()) RETURNING id
            """).param(publicId).query(Long.class).single();
        jdbcClient.sql("""
            INSERT INTO member_identity (provider, subject, member_id, created_at)
            VALUES ('KAKAO', 'legacy', ?, now())
            """).param(memberId).update();

        // when
        Member actual = memberRepository.findByIdentity(LoginProvider.KAKAO, "legacy").orElseThrow();

        // then
        assertThat(actual.nickname()).isEqualTo("관리자1");
    }

    @Test
    void 제공자가_다르면_같은_식별자도_다른_회원이다() {
        // given
        memberRepository.register(newMember("연어팬", LoginProvider.KAKAO, "same-subject"));

        // when
        RegistrationResult actual = memberRepository.register(newMember("버스팬", LoginProvider.APPLE, "same-subject"));

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual).isInstanceOf(RegistrationResult.Registered.class);
            softly.assertThat(memberCount()).isEqualTo(2);
        });
    }

    @Test
    void 대소문자만_다른_닉네임은_이미_쓰인_것으로_알리고_아무것도_저장하지_않는다() {
        // given
        memberRepository.register(newMember("Salmon", LoginProvider.KAKAO, "kakao-1"));

        // when
        RegistrationResult actual = memberRepository.register(newMember("salmon", LoginProvider.KAKAO, "kakao-2"));

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual).isInstanceOf(RegistrationResult.NicknameTaken.class);
            softly.assertThat(memberCount()).isEqualTo(1);
            softly.assertThat(identityCount()).isEqualTo(1);
        });
    }

    @Test
    void 같은_제공자_계정이_이미_있으면_새_회원을_남기지_않는다() {
        // given
        memberRepository.register(newMember("연어팬", LoginProvider.KAKAO, "kakao-1"));

        // when
        RegistrationResult actual = memberRepository.register(newMember("버스팬", LoginProvider.KAKAO, "kakao-1"));

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual).isInstanceOf(RegistrationResult.AlreadyRegistered.class);
            softly.assertThat(memberCount()).isEqualTo(1);
            softly.assertThat(identityCount()).isEqualTo(1);
        });
    }

    @Test
    void 로그인하면_마지막_로그인_시각이_갱신된다() {
        // given
        memberRepository.register(newMember("연어팬", LoginProvider.KAKAO, "kakao-1"));
        Member member = memberRepository.findByIdentity(LoginProvider.KAKAO, "kakao-1").orElseThrow();
        OffsetDateTime later = JOINED_AT.plusDays(3);

        // when
        memberRepository.recordLogin(member.id(), later);

        // then
        OffsetDateTime actual = jdbcClient.sql("SELECT last_login_at FROM member WHERE id = ?")
            .param(member.id())
            .query(OffsetDateTime.class)
            .single();
        assertThat(actual.toInstant()).isEqualTo(later.toInstant());
    }

    @Test
    void 같은_계정이_동시에_처음_로그인해도_회원은_한_명이다() throws Exception {
        // given
        SocialIdentity identity = new SocialIdentity(LoginProvider.KAKAO, "kakao-1");

        // when
        List<SignedInMember> actual = runConcurrently(index -> signIn.signIn(identity));

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.stream().map(SignedInMember::publicId).distinct()).hasSize(1);
            softly.assertThat(actual.stream().filter(SignedInMember::registered)).hasSize(1);
            softly.assertThat(memberCount()).isEqualTo(1);
            softly.assertThat(identityCount()).isEqualTo(1);
        });
    }

    @Test
    void 서로_다른_사람이_같은_닉네임으로_동시에_가입하면_한_명만_성공하고_나머지는_닉네임이_겹친다() throws Exception {
        // when
        List<RegistrationResult> actual = runConcurrently(
            index -> memberRepository.register(newMember("연어팬", LoginProvider.KAKAO, "kakao-" + index)));

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual).filteredOn(RegistrationResult.Registered.class::isInstance).hasSize(1);
            softly.assertThat(actual).filteredOn(RegistrationResult.NicknameTaken.class::isInstance)
                .hasSize(CONCURRENT_REQUESTS - 1);
            softly.assertThat(memberCount()).isEqualTo(1);
            softly.assertThat(identityCount()).isEqualTo(1);
        });
    }

    @Test
    void 닉네임을_바꾸면_다음_조회에도_바뀐_닉네임이다() {
        // given
        NewMember newMember = newMember("연어팬", LoginProvider.KAKAO, "kakao-1");
        memberRepository.register(newMember);

        // when
        NicknameUpdate actual = memberRepository.changeNickname(newMember.publicId(), new Nickname("새이름"));

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual).isEqualTo(NicknameUpdate.UPDATED);
            softly.assertThat(memberRepository.findProfile(newMember.publicId()).orElseThrow().nickname())
                .isEqualTo("새이름");
        });
    }

    @Test
    void 대소문자만_다른_닉네임으로는_바꿀_수_없고_닉네임은_그대로다() {
        // given
        memberRepository.register(newMember("Salmon", LoginProvider.KAKAO, "kakao-1"));
        NewMember other = newMember("버스팬", LoginProvider.KAKAO, "kakao-2");
        memberRepository.register(other);

        // when
        NicknameUpdate actual = memberRepository.changeNickname(other.publicId(), new Nickname("SALMON"));

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual).isEqualTo(NicknameUpdate.TAKEN);
            softly.assertThat(memberRepository.findProfile(other.publicId()).orElseThrow().nickname())
                .isEqualTo("버스팬");
        });
    }

    @Test
    void 자기_닉네임의_대소문자만_바꾸는_것은_허용한다() {
        // given
        NewMember newMember = newMember("Salmon", LoginProvider.KAKAO, "kakao-1");
        memberRepository.register(newMember);

        // when
        NicknameUpdate actual = memberRepository.changeNickname(newMember.publicId(), new Nickname("salmon"));

        // then
        assertThat(actual).isEqualTo(NicknameUpdate.UPDATED);
    }

    @Test
    void 없는_회원의_닉네임은_바꿀_수_없다() {
        // when
        NicknameUpdate actual = memberRepository.changeNickname(UUID.randomUUID(), new Nickname("연어팬"));

        // then
        assertThat(actual).isEqualTo(NicknameUpdate.MEMBER_NOT_FOUND);
    }

    @Test
    void 두_회원이_같은_닉네임으로_동시에_바꾸면_한_명만_성공한다() throws Exception {
        // given
        List<NewMember> members = IntStream.range(0, CONCURRENT_REQUESTS)
            .mapToObj(index -> newMember("회원" + index, LoginProvider.KAKAO, "kakao-" + index))
            .toList();
        members.forEach(memberRepository::register);
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        CountDownLatch start = new CountDownLatch(1);

        // when
        List<Future<NicknameUpdate>> futures = members.stream()
            .map(member -> executor.submit(() -> {
                start.await();
                return memberRepository.changeNickname(member.publicId(), new Nickname("같은이름"));
            }))
            .toList();
        start.countDown();
        List<NicknameUpdate> actual = futures.stream().map(JdbcMemberRepositoryTest::result).toList();
        executor.shutdownNow();

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.stream().filter(NicknameUpdate.UPDATED::equals)).hasSize(1);
            softly.assertThat(actual.stream().filter(NicknameUpdate.TAKEN::equals)).hasSize(CONCURRENT_REQUESTS - 1);
        });
    }

    private <T> List<T> runConcurrently(IntFunction<T> task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        try {
            CountDownLatch ready = new CountDownLatch(CONCURRENT_REQUESTS);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = IntStream.range(0, CONCURRENT_REQUESTS)
                .mapToObj(index -> executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.apply(index);
                }))
                .toList();
            ready.await();
            start.countDown();
            return futures.stream().map(JdbcMemberRepositoryTest::result).toList();
        } finally {
            executor.shutdownNow();
        }
    }

    private static <T> T result(Future<T> future) {
        try {
            return future.get();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static NewMember newMember(String nickname, LoginProvider provider, String subject) {
        return new NewMember(UUID.randomUUID(), new Nickname(nickname), provider, subject, JOINED_AT);
    }

    private long memberCount() {
        return jdbcClient.sql("SELECT count(*) FROM member").query(Long.class).single();
    }

    private long identityCount() {
        return jdbcClient.sql("SELECT count(*) FROM member_identity").query(Long.class).single();
    }
}
