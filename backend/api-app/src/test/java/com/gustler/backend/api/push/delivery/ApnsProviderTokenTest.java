package com.gustler.backend.api.push.delivery;

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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApnsProviderTokenTest {
    private static final Instant START = Instant.parse("2026-11-01T00:00:00Z");

    @TempDir
    Path directory;

    @Test
    void issuesEs256JwtWithKidIssAndIat() throws Exception {
        KeyPair keyPair = TestApnsKeys.ecKeyPair("secp256r1");
        Path keyFile = TestApnsKeys.writePkcs8Pem(keyPair, directory.resolve("AuthKey.p8"));
        ApnsProviderToken token = ApnsProviderToken.load(
            properties(keyFile.toString()),
            Clock.fixed(START, ZoneOffset.UTC)
        );

        SignedJWT jwt = SignedJWT.parse(token.current());
        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        boolean verified = jwt.verify(new ECDSAVerifier((ECPublicKey) keyPair.getPublic()));

        assertSoftly(softly -> {
            softly.assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
            softly.assertThat(jwt.getHeader().getKeyID()).isEqualTo("KEYTEST001");
            softly.assertThat(verified).isTrue();
            softly.assertThat(claims.getIssuer()).isEqualTo("TEAMTEST01");
            softly.assertThat(claims.getIssueTime().toInstant()).isEqualTo(START);
            softly.assertThat(claims.getSubject()).isNull();
            softly.assertThat(claims.getExpirationTime()).isNull();
            softly.assertThat(claims.getAudience()).isEmpty();
        });
    }

    @Test
    void reusesTokenUntilFiftyMinutes() throws Exception {
        Path keyFile = TestApnsKeys.writePkcs8Pem(
            TestApnsKeys.ecKeyPair("secp256r1"),
            directory.resolve("AuthKey.p8")
        );
        MovingClock clock = new MovingClock(START);
        ApnsProviderToken token = ApnsProviderToken.load(properties(keyFile.toString()), clock);

        String first = token.current();
        clock.set(START.plus(Duration.ofMinutes(49).plusSeconds(59)));
        assertThat(token.current()).isEqualTo(first);
        clock.set(START.plus(Duration.ofMinutes(50)));
        assertThat(token.current()).isNotEqualTo(first);
    }

    @Test
    void invalidateClearsOnlyMatchingCache() throws Exception {
        Path keyFile = TestApnsKeys.writePkcs8Pem(
            TestApnsKeys.ecKeyPair("secp256r1"),
            directory.resolve("AuthKey.p8")
        );
        MovingClock clock = new MovingClock(START);
        ApnsProviderToken token = ApnsProviderToken.load(properties(keyFile.toString()), clock);

        String first = token.current();
        token.invalidate("other-token");
        assertThat(token.current()).isEqualTo(first);
        token.invalidate(first);
        clock.set(START.plusSeconds(1));
        assertThat(token.current()).isNotEqualTo(first);
    }

    @Test
    void missingFileDoesNotIncludePathOrCause() {
        String missing = directory.resolve("missing.p8").toString();

        assertThatThrownBy(() -> ApnsProviderToken.load(properties(missing), Clock.fixed(START, ZoneOffset.UTC)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("APNS_PRIVATE_KEY_PATH")
            .hasMessageNotContaining(missing)
            .hasNoCause();
    }

    @Test
    void nonPemFileDoesNotIncludePathOrCause() throws Exception {
        Path garbage = Files.writeString(directory.resolve("garbage.p8"), "not-a-key-secret-content");

        assertThatThrownBy(() -> ApnsProviderToken.load(
            properties(garbage.toString()),
            Clock.fixed(START, ZoneOffset.UTC)
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("APNS_PRIVATE_KEY_PATH")
            .hasMessageNotContaining(garbage.toString())
            .hasMessageNotContaining("secret-content")
            .hasNoCause();
    }

    @Test
    void rsaKeyFailsWithoutPath() throws Exception {
        Path rsa = TestApnsKeys.writePkcs8Pem(TestApnsKeys.rsaKeyPair(), directory.resolve("rsa.p8"));

        assertThatThrownBy(() -> ApnsProviderToken.load(
            properties(rsa.toString()),
            Clock.fixed(START, ZoneOffset.UTC)
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("APNS_PRIVATE_KEY_PATH")
            .hasMessageNotContaining(rsa.toString())
            .hasNoCause();
    }

    @Test
    void p384KeyFailsWithoutPath() throws Exception {
        Path p384 = TestApnsKeys.writePkcs8Pem(TestApnsKeys.ecKeyPair("secp384r1"), directory.resolve("p384.p8"));

        assertThatThrownBy(() -> ApnsProviderToken.load(
            properties(p384.toString()),
            Clock.fixed(START, ZoneOffset.UTC)
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("APNS_PRIVATE_KEY_PATH")
            .hasMessageNotContaining(p384.toString())
            .hasNoCause();
    }

    private static ApnsProperties properties(String privateKeyPath) {
        return new ApnsProperties(
            "TEAMTEST01",
            "KEYTEST001",
            privateKeyPath,
            "com.salmonbus.app",
            "http://127.0.0.1:9",
            "http://127.0.0.1:9"
        );
    }

    private static final class MovingClock extends Clock {
        private Instant instant;

        private MovingClock(Instant instant) {
            this.instant = instant;
        }

        private void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
