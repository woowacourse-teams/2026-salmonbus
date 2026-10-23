package com.gustler.backend.member.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.member.api.LoginProvider;
import com.gustler.backend.member.api.MemberIdentity;
import com.gustler.backend.member.api.MemberProfile;
import com.gustler.backend.member.api.NicknameChangeResult;
import com.gustler.backend.member.api.NicknameViolation;
import com.gustler.backend.member.domain.Member;
import com.gustler.backend.member.domain.MemberRepository;
import com.gustler.backend.member.domain.NewMember;
import com.gustler.backend.member.domain.Nickname;
import com.gustler.backend.member.domain.NicknameUpdate;
import com.gustler.backend.member.domain.RegistrationResult;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NicknameChangerTest {
    private static final UUID MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final RecordingRepository repository = new RecordingRepository();
    private final NicknameChanger changer = new NicknameChanger(repository);

    @Test
    void 규칙에_맞으면_정규화한_닉네임으로_바꾸고_바뀐_정보를_돌려준다() {
        // given
        repository.result = NicknameUpdate.UPDATED;

        // when
        NicknameChangeResult actual = changer.change(MEMBER_ID, "  새이름  ");

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual).isInstanceOf(NicknameChangeResult.Changed.class);
            softly.assertThat(((NicknameChangeResult.Changed) actual).profile().nickname()).isEqualTo("새이름");
            softly.assertThat(repository.requested).isEqualTo("새이름");
        });
    }

    @ParameterizedTest
    @CsvSource({"a,LENGTH", "연어 버스,CHARACTERS", "관리자1,RESERVED"})
    void 규칙을_어기면_저장소를_부르지_않고_이유를_알린다(String nickname, NicknameViolation expected) {
        // when
        NicknameChangeResult actual = changer.change(MEMBER_ID, nickname);

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual).isEqualTo(new NicknameChangeResult.Invalid(expected));
            softly.assertThat(repository.requested).isNull();
        });
    }

    @Test
    void 이미_쓰이는_닉네임이면_Taken이다() {
        // given
        repository.result = NicknameUpdate.TAKEN;

        // when
        NicknameChangeResult actual = changer.change(MEMBER_ID, "연어팬");

        // then
        assertThat(actual).isInstanceOf(NicknameChangeResult.Taken.class);
    }

    @Test
    void 바꾼_직후_회원이_사라졌으면_MemberNotFound다() {
        // given
        repository.result = NicknameUpdate.UPDATED;
        repository.profileExists = false;

        // when
        NicknameChangeResult actual = changer.change(MEMBER_ID, "새이름");

        // then
        assertThat(actual).isInstanceOf(NicknameChangeResult.MemberNotFound.class);
    }

    @Test
    void 없는_회원이면_MemberNotFound다() {
        // given
        repository.result = NicknameUpdate.MEMBER_NOT_FOUND;

        // when
        NicknameChangeResult actual = changer.change(MEMBER_ID, "연어팬");

        // then
        assertThat(actual).isInstanceOf(NicknameChangeResult.MemberNotFound.class);
    }

    private static final class RecordingRepository implements MemberRepository {
        private NicknameUpdate result;
        private String requested;
        private boolean profileExists = true;

        @Override
        public NicknameUpdate changeNickname(UUID publicId, Nickname nickname) {
            requested = nickname.value();
            return result;
        }

        @Override
        public Optional<MemberProfile> findProfile(UUID publicId) {
            if (!profileExists) {
                return Optional.empty();
            }
            return Optional.of(new MemberProfile(publicId, requested, LoginProvider.KAKAO, OffsetDateTime.MIN));
        }

        @Override
        public Optional<Member> findByIdentity(LoginProvider provider, String subject) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RegistrationResult register(NewMember newMember) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void recordLogin(long memberId, OffsetDateTime at) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<MemberIdentity> findIdentity(UUID publicId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<MemberIdentity> delete(UUID publicId) {
            throw new UnsupportedOperationException();
        }
    }
}
