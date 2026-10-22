package com.gustler.backend.api.http.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppleClientAssertionTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-21T03:00:00Z"), ZoneOffset.UTC);
    private static final List<String> SCOPES = List.of("openid");

    @TempDir
    Path directory;

    @Test
    void 고정_시계_기준으로_5분짜리_ES256_JWT를_만든다() throws Exception {
        // given
        KeyPair keyPair = TestAppleKeys.ecKeyPair("secp256r1");
        Path keyFile = TestAppleKeys.writePkcs8Pem(keyPair, directory.resolve("AuthKey.p8"));
        AppleClientAssertion secret = AppleClientAssertion.load(apple(keyFile.toString()), CLOCK);

        // when
        SignedJWT jwt = SignedJWT.parse(secret.issue());

        // then
        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        final boolean signatureVerified = verifies(jwt, keyPair);
        assertSoftly(softly -> {
            softly.assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
            softly.assertThat(jwt.getHeader().getKeyID()).isEqualTo("KEYTEST001");
            softly.assertThat(signatureVerified).isTrue();
            softly.assertThat(claims.getIssuer()).isEqualTo("TEAMTEST01");
            softly.assertThat(claims.getSubject()).isEqualTo("com.salmonbus.test.web");
            softly.assertThat(claims.getAudience()).containsExactly("https://appleid.apple.com");
            softly.assertThat(claims.getIssueTime().toInstant()).isEqualTo(CLOCK.instant());
            softly.assertThat(claims.getExpirationTime().toInstant()).isEqualTo(CLOCK.instant().plusSeconds(300));
        });
    }

    @Test
    void 시계가_흐르면_발급할_때마다_새_시각으로_만든다() throws Exception {
        // given
        Path keyFile = TestAppleKeys.writePkcs8Pem(
            TestAppleKeys.ecKeyPair("secp256r1"), directory.resolve("AuthKey.p8"));
        Instant later = CLOCK.instant().plusSeconds(60);
        AppleClientAssertion first = AppleClientAssertion.load(apple(keyFile.toString()), CLOCK);
        AppleClientAssertion second = AppleClientAssertion.load(
            apple(keyFile.toString()), Clock.fixed(later, ZoneOffset.UTC));

        // when
        Instant firstIssuedAt = SignedJWT.parse(first.issue()).getJWTClaimsSet().getIssueTime().toInstant();
        Instant secondIssuedAt = SignedJWT.parse(second.issue()).getJWTClaimsSet().getIssueTime().toInstant();

        // then
        assertThat(secondIssuedAt).isEqualTo(firstIssuedAt.plusSeconds(60));
    }

    @Test
    void 키_파일이_없으면_경로를_알려_주며_실패한다() {
        // given
        String missing = directory.resolve("missing.p8").toString();

        // when & then
        assertThatThrownBy(() -> AppleClientAssertion.load(apple(missing), CLOCK))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("private-key-path")
            .hasMessageContaining(missing);
    }

    @Test
    void PEM이_아닌_파일은_내용을_드러내지_않고_실패한다() throws Exception {
        // given
        Path garbage = Files.writeString(directory.resolve("garbage.p8"), "not-a-key-secret-content");

        // when & then
        assertThatThrownBy(() -> AppleClientAssertion.load(apple(garbage.toString()), CLOCK))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("PKCS#8")
            .hasMessageNotContaining("secret-content");
    }

    @Test
    void EC가_아닌_키는_읽지_못해_실패한다() throws Exception {
        // given
        Path rsa = TestAppleKeys.writePkcs8Pem(TestAppleKeys.rsaKeyPair(), directory.resolve("rsa.p8"));

        // when & then
        assertThatThrownBy(() -> AppleClientAssertion.load(apple(rsa.toString()), CLOCK))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("PKCS#8");
    }

    @Test
    void P_256이_아닌_EC_키는_실패한다() throws Exception {
        // given
        Path p384 = TestAppleKeys.writePkcs8Pem(TestAppleKeys.ecKeyPair("secp384r1"), directory.resolve("p384.p8"));

        // when & then
        assertThatThrownBy(() -> AppleClientAssertion.load(apple(p384.toString()), CLOCK))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("P-256");
    }

    @Test
    void 빠진_필수_설정은_환경_변수_이름으로_모두_알려_준다() {
        // given
        LoginProperties.Apple apple = new LoginProperties.Apple(
            "", "TEAMTEST01", null, " ", SCOPES, "https://appleid.apple.com", "a", "t", "j", "r");

        // when & then
        assertThatThrownBy(apple::requireComplete)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("AUTH_APPLE_CLIENT_ID, AUTH_APPLE_KEY_ID, AUTH_APPLE_PRIVATE_KEY_PATH")
            .hasMessageNotContaining("AUTH_APPLE_TEAM_ID")
            .hasMessageNotContaining("TEAMTEST01");
    }

    private static boolean verifies(SignedJWT jwt, KeyPair keyPair) throws Exception {
        return jwt.verify(new ECDSAVerifier((ECPublicKey) keyPair.getPublic()));
    }

    private static LoginProperties.Apple apple(String privateKeyPath) {
        return new LoginProperties.Apple(
            "com.salmonbus.test.web", "TEAMTEST01", "KEYTEST001", privateKeyPath, SCOPES,
            "https://appleid.apple.com", "https://appleid.apple.com/auth/authorize",
            "https://appleid.apple.com/auth/token", "https://appleid.apple.com/auth/keys",
            "https://appleid.apple.com/auth/revoke");
    }
}
