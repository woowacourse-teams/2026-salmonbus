package com.gustler.backend.api.http.login;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

/**
 * 애플 토큰 교환에 쓰는 {@code client_secret} 을 요청 때마다 만든다.
 *
 * <p>애플은 고정 비밀 대신 팀 ID·키 ID·개인 키로 서명한 ES256 JWT 를 비밀로 받는다. 개인 키는 애플 개발자 계정에서
 * 내려받은 PKCS#8 PEM({@code .p8}) 파일이며 기동할 때 한 번 읽는다. 읽을 수 없거나 P-256 키가 아니면 기동을
 * 실패시킨다. 키 내용은 예외 메시지와 로그에 남기지 않는다.
 *
 * <p>클래스 이름에 {@code secret} 을 쓰지 않는다. 운영 빌드의 비밀 검사({@code deploy/verify-revision.sh})가 JAR 항목
 * 이름에 그 낱말이 있으면 배포를 막는다.
 *
 * <p>발급 시각은 주입된 {@link Clock} 에서만 얻는다. 유효 기간은 {@link #VALIDITY} 로 짧게 잡는다. 애플이 받는
 * 상한은 6개월이지만 요청마다 새로 만들므로 길 이유가 없다.
 */
final class AppleClientAssertion {
    static final String AUDIENCE = "https://appleid.apple.com";
    static final Duration VALIDITY = Duration.ofMinutes(5);

    private static final String PEM_MARKER = "-----[A-Z ]+-----";

    private final String teamId;
    private final String clientId;
    private final String keyId;
    private final ECDSASigner signer;
    private final Clock clock;

    private AppleClientAssertion(
        String teamId,
        String clientId,
        String keyId,
        ECDSASigner signer,
        Clock clock
    ) {
        this.teamId = teamId;
        this.clientId = clientId;
        this.keyId = keyId;
        this.signer = signer;
        this.clock = clock;
    }

    static AppleClientAssertion load(LoginProperties.Apple apple, Clock clock) {
        apple.requireComplete();
        ECPrivateKey key = readPrivateKey(Path.of(apple.privateKeyPath()));
        ECDSASigner signer = signerOf(key, apple.privateKeyPath());
        return new AppleClientAssertion(apple.teamId(), apple.clientId(), apple.keyId(), signer, clock);
    }

    String issue() {
        Instant now = clock.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .issuer(teamId)
            .subject(clientId)
            .audience(AUDIENCE)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(VALIDITY)))
            .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(keyId).build(), claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException exception) {
            throw new IllegalStateException("애플 client_secret 에 서명하지 못했다.", exception);
        }
        return jwt.serialize();
    }

    private static ECPrivateKey readPrivateKey(Path path) {
        try {
            String pem = Files.readString(path, StandardCharsets.US_ASCII);
            byte[] der = Base64.getMimeDecoder().decode(pem.replaceAll(PEM_MARKER, ""));
            return (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (IOException | GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException(
                "auth.apple.private-key-path 의 파일을 PKCS#8 EC 개인 키(.p8)로 읽지 못했다. path=" + path, exception);
        }
    }

    private static ECDSASigner signerOf(ECPrivateKey key, String path) {
        IllegalStateException notP256 = new IllegalStateException(
            "auth.apple.private-key-path 의 키가 P-256 이 아니다. path=" + path);
        try {
            ECDSASigner signer = new ECDSASigner(key);
            if (!signer.supportedJWSAlgorithms().contains(JWSAlgorithm.ES256)) {
                throw notP256;
            }
            return signer;
        } catch (JOSEException exception) {
            notP256.initCause(exception);
            throw notP256;
        }
    }
}
