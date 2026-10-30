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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 애플 서버 간 알림의 JWT 를 검증하고 {@link AppleAccountEvent} 로 읽는다.
 *
 * <p>서명은 RS256 만 받고 애플 공개키로 확인한다. 발급자를 확인하고, 대상은 받기로 한 값(웹 Services ID, 앱 번들 ID) 중
 * 하나면 된다. 애플은 알림 주소를 기본 App ID 에 등록받으므로 웹으로 가입한 회원의 알림도 대상이 번들 ID 일 수 있다.
 * 알림에는 만료가 없어 요구하지 않고, 있으면 지나지 않았는지만 본다.
 *
 * <p>{@code events} 는 애플 문서에는 객체로 나오지만 JSON 문자열로 온다는 보고가 있어 둘 다 읽는다. 안에 {@code type} 과
 * {@code sub} 가 있어야 한다. 알림 시각 {@code event_time} 은 애플 문서 예시에는 초로, 실제 알림에는 밀리초로 온다는 보고가
 * 있어 크기로 나눠 읽는다. 없으면 받은 시각으로 본다.
 *
 * <p>공개키는 캐시하고, 처음 보는 키 ID 때문에 다시 받는 일은 {@link #JWK_REFRESH_INTERVAL} 에 한 번으로 묶는다. 위조
 * 알림이 키 ID 를 바꿔 가며 들어와도 애플 호출이 늘지 않게 하려는 것이다.
 */
final class AppleNotificationVerifier {
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(60);
    private static final Duration JWK_REFRESH_INTERVAL = Duration.ofSeconds(30);
    private static final int CONNECT_TIMEOUT_MILLIS = 3_000;
    private static final int READ_TIMEOUT_MILLIS = 5_000;
    private static final int JWK_SET_SIZE_LIMIT_BYTES = 64 * 1024;
    private static final String EVENTS = "events";
    private static final long SMALLEST_EPOCH_MILLI = 100_000_000_000L;

    private final NimbusJwtDecoder decoder;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    private AppleNotificationVerifier(NimbusJwtDecoder decoder, ObjectMapper objectMapper, Clock clock) {
        this.decoder = decoder;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    static AppleNotificationVerifier create(
        String issuer,
        Set<String> audiences,
        String jwkSetUri,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSTypeVerifier(new DefaultJOSEObjectTypeVerifier<>(JOSEObjectType.JWT, null));
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwkSource(jwkSetUri)));
        processor.setJWTClaimsSetVerifier((claims, context) -> {
        });
        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
        decoder.setJwtValidator(validator(issuer, audiences, clock));
        return new AppleNotificationVerifier(decoder, objectMapper, clock);
    }

    Verification verify(String payload) {
        if (payload == null || payload.isBlank()) {
            return new Verification.Rejected(Verification.Reason.TOKEN);
        }
        Jwt jwt;
        try {
            jwt = decoder.decode(payload);
        } catch (JwtValidationException exception) {
            return new Verification.Rejected(Verification.Reason.CLAIMS);
        } catch (BadJwtException exception) {
            return new Verification.Rejected(Verification.Reason.TOKEN);
        } catch (JwtException exception) {
            return new Verification.Unavailable();
        }
        Optional<AppleAccountEvent> event = eventOf(jwt.getClaims().get(EVENTS));
        if (event.isEmpty()) {
            return new Verification.Rejected(Verification.Reason.EVENTS);
        }
        return new Verification.Verified(event.get());
    }

    private Optional<AppleAccountEvent> eventOf(Object claim) {
        Map<?, ?> events = switch (claim) {
            case Map<?, ?> object -> object;
            case String json -> objectOf(json);
            case null, default -> Map.of();
        };
        if (events.get("type") instanceof String type && !type.isBlank()
            && events.get("sub") instanceof String subject && !subject.isBlank()) {
            return Optional.of(AppleAccountEvent.of(type, subject, occurredAt(events.get("event_time"))));
        }
        return Optional.empty();
    }

    private Instant occurredAt(Object eventTime) {
        if (!(eventTime instanceof Number number)) {
            return clock.instant();
        }
        long value = number.longValue();
        return value < SMALLEST_EPOCH_MILLI ? Instant.ofEpochSecond(value) : Instant.ofEpochMilli(value);
    }

    private Map<?, ?> objectOf(String json) {
        try {
            Map<?, ?> object = objectMapper.readValue(json, Map.class);
            return object == null ? Map.of() : object;
        } catch (JacksonException exception) {
            return Map.of();
        }
    }

    private static OAuth2TokenValidator<Jwt> validator(String issuer, Set<String> audiences, Clock clock) {
        JwtTimestampValidator timestamps = new JwtTimestampValidator(CLOCK_SKEW);
        timestamps.setClock(clock);
        return new DelegatingOAuth2TokenValidator<>(List.of(
            timestamps,
            new JwtIssuerValidator(issuer),
            new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                claimed -> claimed.stream().anyMatch(audiences::contains))));
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

    /** 검증 결과. 거절 이유는 로그와 지표에만 쓰고 응답에서는 나누지 않는다. 토큰 값과 클레임은 담지 않는다. */
    sealed interface Verification {
        record Verified(AppleAccountEvent event) implements Verification {
        }

        record Rejected(Reason reason) implements Verification {
        }

        record Unavailable() implements Verification {
        }

        enum Reason {
            /** 본문이 서명된 JWT 가 아니거나 서명이 맞지 않는다. */
            TOKEN,
            /** 발급자, 대상, 만료 중 하나가 맞지 않는다. */
            CLAIMS,
            /** {@code events} 가 없거나 그 안에 {@code type}, {@code sub} 가 없다. */
            EVENTS,
        }
    }
}
