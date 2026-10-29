package com.gustler.backend.api.http.login;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.net.MalformedURLException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * 앱이 제공자 SDK 로 받아 보낸 ID 토큰을 검증하고 회원 식별자({@code sub})를 꺼낸다.
 *
 * <p>서명은 RS256 만 받고 제공자 공개키로 확인한다. 발급자·대상·만료를 확인하고, 대상은 웹 로그인과 다른 앱용 값
 * (애플 번들 ID, 카카오 네이티브 앱 키) 하나만 받는다. 시계 오차는 {@link #CLOCK_SKEW} 까지 허용한다.
 *
 * <p>nonce 는 서버가 발급한 값과 비교한다. 애플은 앱이 발급값의 SHA-256(소문자 16진수)을 SDK 에 넣기로 했으므로
 * 토큰의 nonce 가 그 해시여야 하고, 카카오는 발급값 그대로여야 한다. 토큰에 nonce 가 없으면 거절한다. 애플은
 * {@code nonce_supported} 가 거짓인 기기에서 nonce 를 생략할 수 있다고 하지만 iOS 앱은 늘 지원한다.
 *
 * <p>공개키는 캐시하고, 처음 보는 키 ID 때문에 다시 받는 일은 {@link #JWK_REFRESH_INTERVAL} 에 한 번으로 묶는다.
 * 카카오는 공개키를 지나치게 자주 요청하면 막을 수 있다고 안내한다. 위조 토큰이 키 ID 를 바꿔 가며 들어와도
 * 제공자 호출이 늘지 않게 하려는 것이다.
 *
 * <p>검증 실패는 이유를 구분하지 않고 {@link Verification.Rejected} 로, 공개키를 받지 못한 경우는
 * {@link Verification.Unavailable} 로 알린다. 토큰 값과 클레임은 로그에 남기지 않는다.
 */
final class AppIdTokenVerifier {
    static final Duration CLOCK_SKEW = Duration.ofSeconds(60);
    static final Duration JWK_REFRESH_INTERVAL = Duration.ofSeconds(30);

    private static final int CONNECT_TIMEOUT_MILLIS = 3_000;
    private static final int READ_TIMEOUT_MILLIS = 5_000;
    private static final int JWK_SET_SIZE_LIMIT_BYTES = 64 * 1024;

    private final NimbusJwtDecoder decoder;
    private final NonceRule nonceRule;

    private AppIdTokenVerifier(NimbusJwtDecoder decoder, NonceRule nonceRule) {
        this.decoder = decoder;
        this.nonceRule = nonceRule;
    }

    static AppIdTokenVerifier create(
        String issuer,
        String audience,
        String jwkSetUri,
        NonceRule nonceRule,
        Clock clock
    ) {
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSTypeVerifier(new DefaultJOSEObjectTypeVerifier<>(JOSEObjectType.JWT, null));
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwkSource(jwkSetUri)));
        processor.setJWTClaimsSetVerifier((claims, context) -> {
        });
        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
        decoder.setJwtValidator(validator(issuer, audience, clock));
        return new AppIdTokenVerifier(decoder, nonceRule);
    }

    /** 서버가 발급한 {@code nonce} 와 짝이 맞는지까지 확인한다. */
    Verification verify(String idToken, String issuedNonce) {
        return verify(idToken, Optional.of(issuedNonce));
    }

    /**
     * nonce 없이 서명·발급자·대상·만료만 확인한다. 우리 서버가 제공자 토큰 엔드포인트에서 직접 받은 ID 토큰처럼
     * 앱을 거치지 않은 토큰에만 쓴다.
     */
    Verification verifyWithoutNonce(String idToken) {
        return verify(idToken, Optional.empty());
    }

    private Verification verify(String idToken, Optional<String> issuedNonce) {
        if (idToken == null || idToken.isBlank()) {
            return new Verification.Rejected();
        }
        Jwt jwt;
        try {
            jwt = decoder.decode(idToken);
        } catch (BadJwtException exception) {
            return new Verification.Rejected();
        } catch (JwtException exception) {
            return new Verification.Unavailable();
        }
        if (issuedNonce.isPresent() && !nonceRule.matches(jwt.getClaimAsString("nonce"), issuedNonce.get())) {
            return new Verification.Rejected();
        }
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            return new Verification.Rejected();
        }
        return new Verification.Verified(subject);
    }

    private static OAuth2TokenValidator<Jwt> validator(String issuer, String audience, Clock clock) {
        JwtTimestampValidator timestamps = new JwtTimestampValidator(CLOCK_SKEW);
        timestamps.setClock(clock);
        return new DelegatingOAuth2TokenValidator<>(List.of(
            new JwtClaimValidator<>(JwtClaimNames.EXP, expiresAt -> expiresAt != null),
            timestamps,
            new JwtIssuerValidator(issuer),
            new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                audiences -> audiences != null && audiences.contains(audience))));
    }

    private static JWKSource<SecurityContext> jwkSource(String jwkSetUri) {
        DefaultResourceRetriever retriever = new DefaultResourceRetriever(
            CONNECT_TIMEOUT_MILLIS, READ_TIMEOUT_MILLIS, JWK_SET_SIZE_LIMIT_BYTES);
        try {
            return JWKSourceBuilder.create(URI.create(jwkSetUri).toURL(), retriever)
                .rateLimited(JWK_REFRESH_INTERVAL.toMillis())
                .refreshAheadCache(false)
                .build();
        } catch (MalformedURLException | IllegalArgumentException exception) {
            throw new IllegalStateException("공개키 주소가 올바르지 않다. jwk-set-uri=" + jwkSetUri, exception);
        }
    }

    /** 토큰의 nonce 와 서버가 발급한 nonce 를 맞춰 보는 규칙. */
    enum NonceRule {
        /** 앱이 발급값을 그대로 SDK 에 넣는다. 카카오. */
        EXACT {
            @Override
            String expectedClaim(String issuedNonce) {
                return issuedNonce;
            }
        },
        /** 앱이 발급값의 SHA-256 을 소문자 16진수로 SDK 에 넣는다. 애플. */
        SHA256_HEX {
            @Override
            String expectedClaim(String issuedNonce) {
                try {
                    byte[] digest = MessageDigest.getInstance("SHA-256")
                        .digest(issuedNonce.getBytes(StandardCharsets.UTF_8));
                    return HexFormat.of().formatHex(digest);
                } catch (NoSuchAlgorithmException exception) {
                    throw new IllegalStateException("SHA-256 을 쓸 수 없다", exception);
                }
            }
        };

        abstract String expectedClaim(String issuedNonce);

        boolean matches(String claim, String issuedNonce) {
            if (claim == null || issuedNonce == null) {
                return false;
            }
            return MessageDigest.isEqual(
                expectedClaim(issuedNonce).getBytes(StandardCharsets.UTF_8),
                claim.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** 검증 결과. 실패 이유는 응답과 로그에서 구분하지 않는다. */
    sealed interface Verification {
        record Verified(String subject) implements Verification {
        }

        record Rejected() implements Verification {
        }

        record Unavailable() implements Verification {
        }
    }
}
