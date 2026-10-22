package com.gustler.backend.api.http.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.member.api.RefreshTokens;
import com.gustler.backend.member.api.SignInWithSocialAccount;
import com.gustler.backend.member.api.WithdrawMember;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

/** 애플을 켜는 설정이 기동 때 어떻게 검증되고 등록되는지 확인한다. 웹 서버나 DB 는 띄우지 않는다. */
class AppleLoginConfigurationTest {
    @TempDir
    Path directory;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(SocialLoginConfiguration.class)
        .withBean(Clock.class, Clock::systemUTC)
        .withBean(SignInWithSocialAccount.class, () -> identity -> null)
        .withBean(RefreshTokens.class, () -> Mockito.mock(RefreshTokens.class))
        .withBean(WithdrawMember.class, () -> Mockito.mock(WithdrawMember.class))
        .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
        .withPropertyValues(
            "auth.public-base-url=https://www.salmonbus.test",
            "auth.apple.issuer=https://appleid.apple.com",
            "auth.apple.authorization-uri=https://appleid.apple.com/auth/authorize",
            "auth.apple.token-uri=https://appleid.apple.com/auth/token",
            "auth.apple.jwk-set-uri=https://appleid.apple.com/auth/keys",
            "auth.apple.scopes=openid");

    @Test
    void 꺼져_있으면_필수_값이_비어도_기동하고_애플을_등록하지_않는다() {
        // when & then
        runner.withPropertyValues("auth.apple.enabled=false", "auth.apple.client-id=")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertSoftly(softly -> {
                    softly.assertThat(context.getBeansOfType(ClientRegistration.class)).isEmpty();
                    softly.assertThat(context.getBeansOfType(AppleClientAssertion.class)).isEmpty();
                });
            });
    }

    @Test
    void 로그인_전체를_끄면_애플_스위치가_켜져_있어도_애플을_등록하지_않는다() {
        // when & then
        runner.withPropertyValues("auth.enabled=false", "auth.apple.enabled=true")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertSoftly(softly -> {
                    softly.assertThat(context.getBeansOfType(ClientRegistration.class)).isEmpty();
                    softly.assertThat(context.getBeansOfType(AppleClientAssertion.class)).isEmpty();
                });
            });
    }

    @Test
    void 설정이_아예_없어도_기동한다() {
        // when & then
        new ApplicationContextRunner()
            .withUserConfiguration(SocialLoginConfiguration.class)
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(SignInWithSocialAccount.class, () -> identity -> null)
            .withBean(RefreshTokens.class, () -> Mockito.mock(RefreshTokens.class))
            .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void 켜졌는데_필수_값이_비어_있으면_빠진_환경_변수를_알려_주며_기동에_실패한다() {
        // when & then
        runner.withPropertyValues(
                "auth.enabled=true", "auth.apple.enabled=true", "auth.apple.team-id=TEAMTEST01",
                "auth.apple.key-id=KEYTEST001")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(causeMessages(context.getStartupFailure()))
                    .contains("AUTH_APPLE_CLIENT_ID", "AUTH_APPLE_PRIVATE_KEY_PATH")
                    .doesNotContain("AUTH_APPLE_TEAM_ID", "AUTH_APPLE_KEY_ID");
            });
    }

    @Test
    void 켜졌는데_키_파일을_읽을_수_없으면_기동에_실패한다() {
        // given
        String missing = directory.resolve("missing.p8").toString();

        // when & then
        runner.withPropertyValues(enabled(missing)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(causeMessages(context.getStartupFailure())).contains("private-key-path", missing);
        });
    }

    @Test
    void 켜지면_비밀_없는_client_secret_post_등록과_요청_범위가_설정대로_만들어진다() throws Exception {
        // given
        Path keyFile = TestAppleKeys.writePkcs8Pem(
            TestAppleKeys.ecKeyPair("secp256r1"), directory.resolve("AuthKey.p8"));

        // when & then
        runner.withPropertyValues(enabled(keyFile.toString())).run(context -> {
            assertThat(context).hasNotFailed();
            ClientRegistration registration = context.getBean(ClientRegistration.class);
            assertSoftly(softly -> {
                softly.assertThat(registration.getRegistrationId()).isEqualTo("apple");
                softly.assertThat(registration.getClientId()).isEqualTo("com.salmonbus.test.web");
                softly.assertThat(registration.getClientAuthenticationMethod())
                    .isEqualTo(ClientAuthenticationMethod.CLIENT_SECRET_POST);
                softly.assertThat(registration.getClientSecret()).isNullOrEmpty();
                softly.assertThat(registration.getScopes()).containsExactly("openid");
                softly.assertThat(registration.getRedirectUri())
                    .isEqualTo("https://www.salmonbus.test/api/auth/apple/callback");
            });
        });
    }

    @Test
    void 범위에_openid가_없으면_기동에_실패한다() throws Exception {
        // given
        Path keyFile = TestAppleKeys.writePkcs8Pem(
            TestAppleKeys.ecKeyPair("secp256r1"), directory.resolve("AuthKey.p8"));

        // when & then
        runner.withPropertyValues(enabled(keyFile.toString())).withPropertyValues("auth.apple.scopes=profile")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(causeMessages(context.getStartupFailure())).contains("openid");
            });
    }

    private static String[] enabled(String privateKeyPath) {
        return new String[] {
            "auth.enabled=true",
            "auth.apple.enabled=true",
            "auth.apple.client-id=com.salmonbus.test.web",
            "auth.apple.team-id=TEAMTEST01",
            "auth.apple.key-id=KEYTEST001",
            "auth.apple.private-key-path=" + privateKeyPath
        };
    }

    private static String causeMessages(Throwable failure) {
        return Stream.iterate(failure, Objects::nonNull, Throwable::getCause)
            .map(Throwable::getMessage)
            .filter(Objects::nonNull)
            .collect(Collectors.joining("\n"));
    }
}
