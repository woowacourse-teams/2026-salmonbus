package com.gustler.backend.api.account.token;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * 회원의 공개 식별자를 담은 짧은 수명의 액세스 토큰을 만들고 검증한다.
 *
 * <p>토큰에는 {@code sub}(회원 {@code public_id}), 발급자, 발급·만료 시각만 담는다. 닉네임처럼 바뀌는 값은 넣지 않는다.
 * 서버가 즉시 취소할 수 없으므로 수명을 짧게 두고, 취소는 리프레시 토큰 폐기와 회원 조회로 보완한다.
 */
public final class AccessTokens {
    static final String ISSUER = "salmonbus";

    /** 서버 사이 시계 차이를 견디는 허용 오차. 기본값 60초는 15분 수명에 비해 크다. */
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(5);

    private final Clock clock;
    private final TokenProperties properties;
    private final NimbusJwtEncoder encoder;
    private final NimbusJwtDecoder decoder;

    public AccessTokens(TokenProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        SecretKey key = new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        OctetSequenceKey jwk = new OctetSequenceKey.Builder(key)
            .keyID(properties.keyId())
            .algorithm(JWSAlgorithm.HS256)
            .build();
        this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
        this.decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator(CLOCK_SKEW);
        timestamps.setClock(clock);
        this.decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            timestamps, new JwtIssuerValidator(ISSUER)));
    }

    public String issue(UUID publicId) {
        Instant now = clock.instant();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).keyId(properties.keyId()).build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuer(ISSUER)
            .subject(publicId.toString())
            .issuedAt(now)
            .expiresAt(now.plus(properties.accessTtl()))
            .build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /** 서명·발급자·만료가 맞으면 회원 식별자를 돌려준다. 어떤 이유로든 검증에 실패하면 비어 있다. */
    public Optional<UUID> verify(String token) {
        try {
            return Optional.of(UUID.fromString(decoder.decode(token).getSubject()));
        } catch (JwtException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public JwtDecoder decoder() {
        return decoder;
    }

    public long accessTtlSeconds() {
        return properties.accessTtl().toSeconds();
    }

}
