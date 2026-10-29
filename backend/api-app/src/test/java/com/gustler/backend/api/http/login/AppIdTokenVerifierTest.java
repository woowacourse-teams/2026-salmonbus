package com.gustler.backend.api.http.login;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.http.login.AppIdTokenVerifier.NonceRule;
import com.gustler.backend.api.http.login.AppIdTokenVerifier.Verification;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AppIdTokenVerifierTest {
    private static final Instant NOW = Instant.parse("2026-10-30T03:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String ISSUER = "https://issuer.test";
    private static final String AUDIENCE = "com.salmonbus.test.app";
    private static final String NONCE = "server-issued-nonce";

    private HttpServer server;
    private RSAKey signingKey;
    private final AtomicInteger jwksRequests = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/keys", exchange -> {
            jwksRequests.incrementAndGet();
            byte[] body = new JWKSet(signingKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void 애플_규칙이면_토큰의_nonce가_발급값의_SHA256_16진수여야_한다() throws Exception {
        // given
        AppIdTokenVerifier verifier = verifier(NonceRule.SHA256_HEX);
        assertThat(NonceRule.SHA256_HEX.expectedClaim("abc"))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        String hashed = signed(claims -> claims.claim("nonce", NonceRule.SHA256_HEX.expectedClaim(NONCE)));
        String raw = signed(claims -> claims.claim("nonce", NONCE));

        // when
        Verification hashedResult = verifier.verify(hashed, NONCE);
        Verification rawResult = verifier.verify(raw, NONCE);

        // then
        assertThat(hashedResult).isEqualTo(new Verification.Verified("member-sub"));
        assertThat(rawResult).isInstanceOf(Verification.Rejected.class);
    }

    @Test
    void nonce가_다르거나_없으면_거절한다() throws Exception {
        // given
        AppIdTokenVerifier verifier = verifier(NonceRule.EXACT);
        String other = signed(claims -> claims.claim("nonce", "other-nonce"));
        String missing = signed(claims -> {
        });

        // when & then
        assertThat(verifier.verify(other, NONCE)).isInstanceOf(Verification.Rejected.class);
        assertThat(verifier.verify(missing, NONCE)).isInstanceOf(Verification.Rejected.class);
    }

    @Test
    void 발급자나_대상이_다르면_거절한다() throws Exception {
        // given
        AppIdTokenVerifier verifier = verifier(NonceRule.EXACT);
        String otherIssuer = signed(claims -> claims.claim("nonce", NONCE).issuer("https://other.test"));
        String webAudience = signed(claims -> claims.claim("nonce", NONCE).audience("com.salmonbus.test.web"));

        // when & then
        assertThat(verifier.verify(otherIssuer, NONCE)).isInstanceOf(Verification.Rejected.class);
        assertThat(verifier.verify(webAudience, NONCE)).isInstanceOf(Verification.Rejected.class);
    }

    @Test
    void 만료가_허용_오차를_넘었거나_만료_시각이_없으면_거절한다() throws Exception {
        // given
        AppIdTokenVerifier verifier = verifier(NonceRule.EXACT);
        Date issuedEarlier = Date.from(NOW.minusSeconds(600));
        String withinSkew = signed(claims -> claims.claim("nonce", NONCE).issueTime(issuedEarlier)
            .expirationTime(Date.from(NOW.minus(AppIdTokenVerifier.CLOCK_SKEW).plusSeconds(1))));
        String expired = signed(claims -> claims.claim("nonce", NONCE).issueTime(issuedEarlier)
            .expirationTime(Date.from(NOW.minus(AppIdTokenVerifier.CLOCK_SKEW).minusSeconds(1))));
        String noExpiry = signed(claims -> claims.claim("nonce", NONCE).expirationTime(null));

        // when & then
        assertThat(verifier.verify(withinSkew, NONCE)).isInstanceOf(Verification.Verified.class);
        assertThat(verifier.verify(expired, NONCE)).isInstanceOf(Verification.Rejected.class);
        assertThat(verifier.verify(noExpiry, NONCE)).isInstanceOf(Verification.Rejected.class);
    }

    @Test
    void 제공자_공개키가_아닌_키로_서명했거나_RS256이_아니면_거절한다() throws Exception {
        // given
        AppIdTokenVerifier verifier = verifier(NonceRule.EXACT);
        RSAKey impostor = new RSAKeyGenerator(2048).keyID("test-key").generate();
        String forged = signedWith(impostor, claims -> claims.claim("nonce", NONCE));
        SignedJWT hmac = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("test-key").build(),
            claims(claims -> claims.claim("nonce", NONCE)));
        hmac.sign(new MACSigner("0123456789abcdef0123456789abcdef"));
        String unsigned = new PlainJWT(claims(claims -> claims.claim("nonce", NONCE))).serialize();

        // when & then
        assertThat(verifier.verify(forged, NONCE)).isInstanceOf(Verification.Rejected.class);
        assertThat(verifier.verify(hmac.serialize(), NONCE)).isInstanceOf(Verification.Rejected.class);
        assertThat(verifier.verify(unsigned, NONCE)).isInstanceOf(Verification.Rejected.class);
    }

    @Test
    void 형식이_틀렸거나_비었거나_회원_식별자가_없으면_거절한다() throws Exception {
        // given
        AppIdTokenVerifier verifier = verifier(NonceRule.EXACT);
        String noSubject = signed(claims -> claims.claim("nonce", NONCE).subject(null));

        // when & then
        assertThat(verifier.verify("not-a-jwt", NONCE)).isInstanceOf(Verification.Rejected.class);
        assertThat(verifier.verify(" ", NONCE)).isInstanceOf(Verification.Rejected.class);
        assertThat(verifier.verify(null, NONCE)).isInstanceOf(Verification.Rejected.class);
        assertThat(verifier.verify(noSubject, NONCE)).isInstanceOf(Verification.Rejected.class);
    }

    @Test
    void 처음_보는_키_ID가_연달아_와도_공개키는_제한_간격_안에서_한_번만_다시_받는다() throws Exception {
        // given
        AppIdTokenVerifier verifier = verifier(NonceRule.EXACT);
        verifier.verify(signed(claims -> claims.claim("nonce", NONCE)), NONCE);
        int afterFirstFetch = jwksRequests.get();

        // when
        for (int attempt = 0; attempt < 5; attempt++) {
            RSAKey unknown = new RSAKeyGenerator(2048).keyID("unknown-" + attempt).generate();
            verifier.verify(signedWith(unknown, claims -> claims.claim("nonce", NONCE)), NONCE);
        }

        // then
        assertThat(jwksRequests.get() - afterFirstFetch).isLessThanOrEqualTo(1);
    }

    private AppIdTokenVerifier verifier(NonceRule rule) {
        return AppIdTokenVerifier.create(ISSUER, AUDIENCE, baseUrl() + "/keys", rule, CLOCK);
    }

    private String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private String signed(Consumer<JWTClaimsSet.Builder> mutation) throws Exception {
        return signedWith(signingKey, mutation);
    }

    private static String signedWith(RSAKey key, Consumer<JWTClaimsSet.Builder> mutation) throws Exception {
        SignedJWT jwt = new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims(mutation));
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static JWTClaimsSet claims(Consumer<JWTClaimsSet.Builder> mutation) {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .audience(AUDIENCE)
            .subject("member-sub")
            .issueTime(Date.from(NOW))
            .expirationTime(Date.from(NOW.plusSeconds(600)));
        mutation.accept(claims);
        return claims.build();
    }
}
