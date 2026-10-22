package com.gustler.backend.member.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.member.domain.RefreshTokenCodec;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RefreshTokenConfigurationTest {
    private static final String SECRET_32_BYTES = "0123456789abcdef0123456789abcdef";
    private static final UUID FAMILY_ID = UUID.fromString("0b7e1c3a-5d2f-4a86-9e41-7c3d2b9f6a10");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(RefreshTokenConfiguration.class);

    @Test
    void 로그인이_켜져_있고_비밀이_충분하면_그_비밀로_코덱을_만든다() {
        // when & then
        runner.withPropertyValues("auth.enabled=true", "auth.refresh-token.secret=" + SECRET_32_BYTES)
            .run(context -> {
                RefreshTokenCodec same = new RefreshTokenCodec(SECRET_32_BYTES.getBytes());
                RefreshTokenCodec codec = context.getBean(RefreshTokenCodec.class);
                assertThat(codec.encode(FAMILY_ID, 1)).isEqualTo(same.encode(FAMILY_ID, 1));
            });
    }

    @Test
    void 로그인이_켜져_있는데_비밀이_없으면_환경_변수_이름을_알리며_기동에_실패한다() {
        // when & then
        runner.withPropertyValues("auth.enabled=true")
            .run(context -> assertSoftly(softly -> {
                softly.assertThat(context.getStartupFailure()).isNotNull();
                softly.assertThat(rootMessage(context.getStartupFailure())).contains("AUTH_REFRESH_TOKEN_SECRET");
            }));
    }

    @Test
    void 로그인이_켜져_있는데_비밀이_32바이트보다_짧으면_기동에_실패한다() {
        // when & then
        runner.withPropertyValues("auth.enabled=true", "auth.refresh-token.secret=0123456789abcdef0123456789abcde")
            .run(context -> assertSoftly(softly -> {
                softly.assertThat(context.getStartupFailure()).isNotNull();
                softly.assertThat(rootMessage(context.getStartupFailure())).contains("AUTH_REFRESH_TOKEN_SECRET");
            }));
    }

    @Test
    void 글자_수가_아니라_UTF_8_바이트_길이로_센다() {
        // given
        String elevenKoreanCharacters = "가나다라마바사아자차카";

        // when & then
        runner.withPropertyValues("auth.enabled=true", "auth.refresh-token.secret=" + elevenKoreanCharacters)
            .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void 로그인이_꺼져_있으면_비밀이_없어도_기동하고_프로세스마다_다른_키를_쓴다() {
        // when & then
        runner.run(first -> runner.run(second -> {
            String token = first.getBean(RefreshTokenCodec.class).encode(FAMILY_ID, 1);
            assertSoftly(softly -> {
                softly.assertThat(first.getStartupFailure()).isNull();
                softly.assertThat(second.getBean(RefreshTokenCodec.class).decode(token)).isEmpty();
            });
        }));
    }

    private static String rootMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }
}
