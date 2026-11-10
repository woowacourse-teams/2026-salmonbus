package com.gustler.backend.api.push.delivery;

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

final class ApnsProviderToken {
    static final Duration REUSE = Duration.ofMinutes(50);

    private static final String PEM_MARKER = "-----[A-Z ]+-----";

    private final String teamId;
    private final String keyId;
    private final ECDSASigner signer;
    private final Clock clock;
    private String cached;
    private Instant issuedAt;

    private ApnsProviderToken(String teamId, String keyId, ECDSASigner signer, Clock clock) {
        this.teamId = teamId;
        this.keyId = keyId;
        this.signer = signer;
        this.clock = clock;
    }

    static ApnsProviderToken load(ApnsProperties properties, Clock clock) {
        ECPrivateKey key = readPrivateKey(Path.of(properties.privateKeyPath()));
        return new ApnsProviderToken(properties.teamId(), properties.keyId(), signerOf(key), clock);
    }

    synchronized String current() {
        Instant now = clock.instant();
        if (cached == null || !now.isBefore(issuedAt.plus(REUSE))) {
            cached = sign(now);
            issuedAt = now;
        }
        return cached;
    }

    synchronized void invalidate(String used) {
        if (used != null && used.equals(cached)) {
            cached = null;
        }
    }

    private String sign(Instant now) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .issuer(teamId)
            .issueTime(Date.from(now))
            .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(keyId).build(), claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException exception) {
            throw new IllegalStateException(
                "APNS_PRIVATE_KEY_PATH 의 파일로 provider token에 서명하지 못했다. cause="
                    + exception.getClass().getSimpleName());
        }
        return jwt.serialize();
    }

    private static ECPrivateKey readPrivateKey(Path path) {
        try {
            String pem = Files.readString(path, StandardCharsets.US_ASCII);
            byte[] der = Base64.getMimeDecoder().decode(pem.replaceAll(PEM_MARKER, ""));
            return (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (IOException | GeneralSecurityException | ClassCastException | IllegalArgumentException exception) {
            throw new IllegalStateException(
                "APNS_PRIVATE_KEY_PATH 의 파일을 PKCS#8 EC 개인 키(.p8)로 읽지 못했다. cause="
                    + exception.getClass().getSimpleName());
        }
    }

    private static ECDSASigner signerOf(ECPrivateKey key) {
        try {
            ECDSASigner signer = new ECDSASigner(key);
            if (!signer.supportedJWSAlgorithms().contains(JWSAlgorithm.ES256)) {
                throw new IllegalStateException("APNS_PRIVATE_KEY_PATH 의 키가 P-256 이 아니다. cause=UnsupportedCurve");
            }
            return signer;
        } catch (JOSEException exception) {
            throw new IllegalStateException(
                "APNS_PRIVATE_KEY_PATH 의 키가 P-256 이 아니다. cause=" + exception.getClass().getSimpleName());
        }
    }
}
