package com.gustler.backend.api.http.login;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.http.login.AppleNotificationVerifier.Verification;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class AppleNotificationVerifierTest {
    private static final Instant NOW = Instant.parse("2026-10-30T03:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String ISSUER = "https://issuer.test";
    private static final String AUDIENCE = "com.salmonbus.test.web";
    private static final String SUBJECT = "member-sub";

    private HttpServer server;
    private RSAKey signingKey;

    @BeforeEach
    void setUp() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("apple-test-key").generate();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/keys", exchange -> {
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
    void 공개키_주소에_연결하지_못하면_Unavailable이다() throws Exception {
        // given
        AppleNotificationVerifier verifier = verifier("http://localhost:" + closedPort() + "/keys");

        // when
        Verification actual = verifier.verify(signedWith(signingKey));

        // then
        assertThat(actual).isInstanceOf(Verification.Unavailable.class);
    }

    /** 누구나 보낼 수 있는 위조 알림으로도 503 이 된다. RUNBOOK 의 {@code result=unavailable} 설명이 이 동작을 전제로 한다. */
    @Test
    void 공개키를_받은_뒤_제한_간격_안에_처음_보는_키_ID가_이어서_오면_첫_번째는_거절하고_두_번째부터_Unavailable이다() throws Exception {
        // given
        AppleNotificationVerifier verifier = verifier("http://localhost:" + server.getAddress().getPort() + "/keys");
        String known = signedWith(signingKey);
        verifier.verify(known);
        String firstUnknown = signedWith(new RSAKeyGenerator(2048).keyID("unknown-1").generate());
        String secondUnknown = signedWith(new RSAKeyGenerator(2048).keyID("unknown-2").generate());

        // when
        Verification first = verifier.verify(firstUnknown);
        Verification second = verifier.verify(secondUnknown);
        Verification knownAgain = verifier.verify(known);

        // then
        assertThat(first).isEqualTo(new Verification.Rejected(Verification.Reason.TOKEN));
        assertThat(second).isInstanceOf(Verification.Unavailable.class);
        assertThat(knownAgain).isEqualTo(
            new Verification.Verified(new AppleAccountEvent.AccountDeleted(SUBJECT)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 연결_해제_알림의_시각은_초로_오든_밀리초로_오든_같은_시각으로_읽는다(boolean inMillis) throws Exception {
        // given
        AppleNotificationVerifier verifier = verifier("http://localhost:" + server.getAddress().getPort() + "/keys");
        Instant revokedAt = NOW.minusSeconds(90);
        long eventTime = inMillis ? revokedAt.toEpochMilli() : revokedAt.getEpochSecond();

        // when
        Verification actual = verifier.verify(signedWith(signingKey, consentRevoked(eventTime)));

        // then
        assertThat(actual).isEqualTo(
            new Verification.Verified(new AppleAccountEvent.ConsentRevoked(SUBJECT, revokedAt)));
    }

    @Test
    void 연결_해제_알림에_시각이_없으면_받은_시각으로_읽는다() throws Exception {
        // given
        AppleNotificationVerifier verifier = verifier("http://localhost:" + server.getAddress().getPort() + "/keys");

        // when
        Verification actual = verifier.verify(signedWith(signingKey, consentRevoked(null)));

        // then
        assertThat(actual).isEqualTo(
            new Verification.Verified(new AppleAccountEvent.ConsentRevoked(SUBJECT, NOW)));
    }

    private static Map<String, Object> consentRevoked(Long eventTime) {
        Map<String, Object> events = new HashMap<>(Map.of("type", "consent-revoked", "sub", SUBJECT));
        if (eventTime != null) {
            events.put("event_time", eventTime);
        }
        return events;
    }

    private static AppleNotificationVerifier verifier(String jwkSetUri) {
        return AppleNotificationVerifier.create(ISSUER, Set.of(AUDIENCE), jwkSetUri, new ObjectMapper(), CLOCK);
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String signedWith(RSAKey key) throws Exception {
        return signedWith(key, Map.of("type", "account-deleted", "sub", SUBJECT));
    }

    private static String signedWith(RSAKey key, Map<String, Object> events) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .audience(AUDIENCE)
            .issueTime(Date.from(NOW))
            .claim("events", events)
            .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
