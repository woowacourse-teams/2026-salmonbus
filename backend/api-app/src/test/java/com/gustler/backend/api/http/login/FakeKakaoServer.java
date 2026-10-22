package com.gustler.backend.api.http.login;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 테스트용 카카오 OIDC 서버. 토큰 교환과 공개키만 제공하고 실제 카카오는 부르지 않는다.
 *
 * <p>코드를 발급할 때 인가 요청의 {@code nonce} 와 {@code code_challenge} 를 함께 등록한다. 토큰 요청이 오면
 * PKCE 검증값이 맞는지 확인한 뒤 등록한 값으로 ID 토큰에 서명한다.
 */
final class FakeKakaoServer implements AutoCloseable {
    static final String CLIENT_ID = "test-rest-api-key";
    static final String CLIENT_SECRET = "test-client-secret";

    private final HttpServer server;
    private final RSAKey signingKey;
    private final RSAKey otherKey;
    private final Map<String, Grant> grants = new ConcurrentHashMap<>();
    private final List<TokenRequest> tokenRequests = new ArrayList<>();
    private volatile int tokenStatus = HttpStatus.OK.value();
    private final List<TokenRequest> unlinkRequests = new ArrayList<>();
    private volatile int unlinkStatus = HttpStatus.OK.value();
    private volatile String unlinkBody = "{\"id\":1}";

    FakeKakaoServer() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
        otherKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/oauth/token", this::token);
        server.createContext("/jwks.json", this::jwks);
        server.createContext("/v1/user/unlink", this::unlink);
        server.start();
    }

    String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    /** 토큰 교환을 이 상태 코드로 거절하게 한다. 200 이면 정상이다. */
    void failTokenExchangeWith(int status) {
        tokenStatus = status;
    }

    void reset() {
        grants.clear();
        synchronized (tokenRequests) {
            tokenRequests.clear();
        }
        tokenStatus = HttpStatus.OK.value();
        synchronized (unlinkRequests) {
            unlinkRequests.clear();
        }
        unlinkStatus = HttpStatus.OK.value();
        unlinkBody = "{\"id\":1}";
    }

    /** 연결 해제 요청에 이 상태 코드와 본문으로 답하게 한다. */
    void answerUnlinkWith(int status, String body) {
        unlinkStatus = status;
        unlinkBody = body;
    }

    List<TokenRequest> unlinkRequests() {
        synchronized (unlinkRequests) {
            return List.copyOf(unlinkRequests);
        }
    }

    private void unlink(HttpExchange exchange) throws IOException {
        Map<String, String> form = form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        synchronized (unlinkRequests) {
            unlinkRequests.add(new TokenRequest(form, exchange.getRequestHeaders().getFirst("Authorization")));
        }
        respond(exchange, unlinkStatus, unlinkBody);
    }

    List<TokenRequest> tokenRequests() {
        synchronized (tokenRequests) {
            return List.copyOf(tokenRequests);
        }
    }

    /** 사용자가 제공자 화면에서 동의했을 때 제공자가 콜백에 실어 보낼 코드를 만든다. */
    String authorize(String authorizationLocation, String sub) {
        return authorize(authorizationLocation, claims -> claims.subject(sub), false);
    }

    String authorize(String authorizationLocation, Consumer<JWTClaimsSet.Builder> mutation, boolean signWithOtherKey) {
        MultiValueMap<String, String> query = UriComponentsBuilder.fromUriString(authorizationLocation)
            .build().getQueryParams();
        String code = UUID.randomUUID().toString();
        grants.put(code, new Grant(
            query.getFirst("nonce"), query.getFirst("code_challenge"), mutation, signWithOtherKey));
        return code;
    }

    private void token(HttpExchange exchange) throws IOException {
        Map<String, String> form = form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        synchronized (tokenRequests) {
            tokenRequests.add(new TokenRequest(form, exchange.getRequestHeaders().getFirst("Authorization")));
        }
        Grant grant = grants.get(form.get("code"));
        if (tokenStatus != HttpStatus.OK.value()) {
            respond(exchange, tokenStatus, "{\"error\":\"server_error\"}");
            return;
        }
        final boolean authenticated = CLIENT_SECRET.equals(form.get("client_secret"));
        if (grant == null || !authenticated || !grant.matches(form.get("code_verifier"))) {
            respond(exchange, HttpStatus.BAD_REQUEST.value(), "{\"error\":\"invalid_grant\"}");
            return;
        }
        try {
            String idToken = idToken(grant);
            respond(exchange, HttpStatus.OK.value(), "{\"access_token\":\"fake-access-token\","
                + "\"token_type\":\"bearer\",\"expires_in\":21599,"
                + "\"id_token\":\"" + idToken + "\",\"scope\":\"openid\"}");
        } catch (JOSEException exception) {
            throw new IOException(exception);
        }
    }

    private String idToken(Grant grant) throws JOSEException {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
            .issuer(baseUrl())
            .audience(CLIENT_ID)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(300)))
            .claim("nonce", grant.nonce());
        grant.mutation().accept(claims);
        RSAKey key = signingKey;
        if (grant.signWithOtherKey()) {
            key = otherKey;
        }
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build();
        SignedJWT jwt = new SignedJWT(header, claims.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private void jwks(HttpExchange exchange) throws IOException {
        respond(exchange, HttpStatus.OK.value(), new JWKSet(signingKey.toPublicJWK()).toString());
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static Map<String, String> form(String body) {
        return Stream.of(body.split("&"))
            .map(pair -> (pair + "=").split("=", 3))
            .collect(Collectors.toMap(pair -> decode(pair[0]), pair -> decode(pair[1]), (first, second) -> second));
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    record TokenRequest(Map<String, String> form, String authorizationHeader) {
    }

    private record Grant(
        String nonce,
        String codeChallenge,
        Consumer<JWTClaimsSet.Builder> mutation,
        boolean signWithOtherKey
    ) {
        boolean matches(String codeVerifier) {
            if (codeVerifier == null) {
                return false;
            }
            try {
                byte[] verifier = codeVerifier.getBytes(StandardCharsets.US_ASCII);
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier);
                return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).equals(codeChallenge);
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException(exception);
            }
        }
    }
}
