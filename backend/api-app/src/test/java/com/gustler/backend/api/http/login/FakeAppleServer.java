package com.gustler.backend.api.http.login;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSAVerifier;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.interfaces.ECPublicKey;
import java.text.ParseException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 테스트용 애플 로그인 서버. 토큰 교환과 공개키만 제공하고 실제 애플은 부르지 않는다.
 *
 * <p>애플 개인 키 대신 테스트가 직접 만든 EC P-256 키를 {@code .p8} 형식 임시 파일로 내려 준다. 토큰 요청이 오면
 * 그 공개키로 {@code client_secret} 서명과 클레임을 검증하고, 맞아야 등록한 값으로 ID 토큰에 서명한다. 애플은
 * PKCE 를 지원하지 않으므로 {@code code_verifier} 는 검증하지 않고 요청에 실린 그대로 기록만 한다.
 */
final class FakeAppleServer implements AutoCloseable {
    static final String CLIENT_ID = "com.salmonbus.test.web";
    static final String TEAM_ID = "TEAMTEST01";
    static final String KEY_ID = "KEYTEST001";
    static final String ACCESS_TOKEN = "fake-apple-access-token";

    private final HttpServer server;
    private final RSAKey signingKey;
    private final RSAKey otherKey;
    private final KeyPair clientSecretKey;
    private final Path privateKeyFile;
    private final Map<String, Grant> grants = new ConcurrentHashMap<>();
    private final List<TokenRequest> tokenRequests = new ArrayList<>();
    private final List<String> idTokens = new ArrayList<>();
    private volatile int tokenStatus = HttpStatus.OK.value();
    private final List<TokenRequest> revokeRequests = new ArrayList<>();
    private volatile int revokeStatus = HttpStatus.OK.value();

    FakeAppleServer() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("apple-test-key").generate();
        otherKey = new RSAKeyGenerator(2048).keyID("apple-test-key").generate();
        clientSecretKey = TestAppleKeys.ecKeyPair("secp256r1");
        privateKeyFile = TestAppleKeys.writePkcs8Pem(clientSecretKey, Files.createTempFile("apple-test-key-", ".p8"));
        privateKeyFile.toFile().deleteOnExit();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/auth/token", this::token);
        server.createContext("/auth/keys", this::jwks);
        server.createContext("/auth/revoke", this::revoke);
        server.start();
    }

    String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    /** 애플 개발자 계정에서 내려받은 {@code .p8} 파일을 대신하는 임시 파일. */
    Path privateKeyFile() {
        return privateKeyFile;
    }

    ECPublicKey clientSecretPublicKey() {
        return (ECPublicKey) clientSecretKey.getPublic();
    }

    /** 토큰 교환을 이 상태 코드로 거절하게 한다. 200 이면 정상이다. */
    void failTokenExchangeWith(int status) {
        tokenStatus = status;
    }

    void reset() {
        grants.clear();
        synchronized (tokenRequests) {
            tokenRequests.clear();
            idTokens.clear();
        }
        tokenStatus = HttpStatus.OK.value();
        synchronized (revokeRequests) {
            revokeRequests.clear();
        }
        revokeStatus = HttpStatus.OK.value();
    }

    /** 토큰 취소 요청을 이 상태 코드로 답하게 한다. 200 이면 정상이다. */
    void answerRevokeWith(int status) {
        revokeStatus = status;
    }

    /** 받은 토큰 취소 요청. {@code clientSecretValid} 는 요청의 {@code client_secret} 이 올바르게 서명됐는지다. */
    List<RevokeRequest> revokeRequests() {
        synchronized (revokeRequests) {
            return revokeRequests.stream()
                .map(request -> new RevokeRequest(
                    request.single("token"), request.single("token_type_hint"), request.single("client_id"),
                    clientSecretIsValid(request.form())))
                .toList();
        }
    }

    private void revoke(HttpExchange exchange) throws IOException {
        Map<String, List<String>> form = form(new String(exchange.getRequestBody().readAllBytes(),
            StandardCharsets.UTF_8));
        synchronized (revokeRequests) {
            revokeRequests.add(new TokenRequest(form, exchange.getRequestHeaders().getFirst("Authorization")));
        }
        if (revokeStatus != HttpStatus.OK.value()) {
            respond(exchange, revokeStatus, "{\"error\":\"server_error\"}");
            return;
        }
        if (!clientSecretIsValid(form)) {
            respond(exchange, HttpStatus.BAD_REQUEST.value(), "{\"error\":\"invalid_client\"}");
            return;
        }
        respond(exchange, HttpStatus.OK.value(), "");
    }

    List<TokenRequest> tokenRequests() {
        synchronized (tokenRequests) {
            return List.copyOf(tokenRequests);
        }
    }

    /** 이 서버가 발급해 응답에 실어 보낸 ID 토큰들. 로그에 남지 않았는지 확인하는 데 쓴다. */
    List<String> issuedIdTokens() {
        synchronized (tokenRequests) {
            return List.copyOf(idTokens);
        }
    }

    /** 사용자가 애플 화면에서 동의했을 때 애플이 콜백에 실어 보낼 코드를 만든다. */
    String authorize(String authorizationLocation, String sub) {
        return authorize(authorizationLocation, claims -> claims.subject(sub), false);
    }

    String authorize(String authorizationLocation, Consumer<JWTClaimsSet.Builder> mutation, boolean signWithOtherKey) {
        MultiValueMap<String, String> query = UriComponentsBuilder.fromUriString(authorizationLocation)
            .build().getQueryParams();
        String code = UUID.randomUUID().toString();
        grants.put(code, new Grant(query.getFirst("nonce"), mutation, signWithOtherKey));
        return code;
    }

    private void token(HttpExchange exchange) throws IOException {
        Map<String, List<String>> form = form(new String(exchange.getRequestBody().readAllBytes(),
            StandardCharsets.UTF_8));
        synchronized (tokenRequests) {
            tokenRequests.add(new TokenRequest(form, exchange.getRequestHeaders().getFirst("Authorization")));
        }
        if (tokenStatus != HttpStatus.OK.value()) {
            respond(exchange, tokenStatus, "{\"error\":\"server_error\"}");
            return;
        }
        Grant grant = grants.get(first(form, "code"));
        if (grant == null || !clientSecretIsValid(form)) {
            respond(exchange, HttpStatus.BAD_REQUEST.value(), "{\"error\":\"invalid_client\"}");
            return;
        }
        try {
            String idToken = idToken(grant);
            synchronized (tokenRequests) {
                idTokens.add(idToken);
            }
            respond(exchange, HttpStatus.OK.value(), "{\"access_token\":\"" + ACCESS_TOKEN + "\","
                + "\"token_type\":\"Bearer\",\"expires_in\":3600,"
                + "\"refresh_token\":\"fake-apple-refresh-token\",\"id_token\":\"" + idToken + "\"}");
        } catch (JOSEException exception) {
            throw new IOException(exception);
        }
    }

    private boolean clientSecretIsValid(Map<String, List<String>> form) {
        List<String> secrets = form.getOrDefault("client_secret", List.of());
        if (secrets.size() != 1 || !CLIENT_ID.equals(first(form, "client_id"))) {
            return false;
        }
        try {
            SignedJWT jwt = SignedJWT.parse(secrets.get(0));
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            return jwt.verify(new ECDSAVerifier(clientSecretPublicKey()))
                && JWSAlgorithm.ES256.equals(jwt.getHeader().getAlgorithm())
                && KEY_ID.equals(jwt.getHeader().getKeyID())
                && TEAM_ID.equals(claims.getIssuer())
                && CLIENT_ID.equals(claims.getSubject())
                && claims.getAudience().equals(List.of("https://appleid.apple.com"))
                && claims.getExpirationTime().toInstant().isAfter(Instant.now());
        } catch (ParseException | JOSEException exception) {
            return false;
        }
    }

    private String idToken(Grant grant) throws JOSEException {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
            .issuer(baseUrl())
            .audience(CLIENT_ID)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(300)))
            .claim("nonce", grant.nonce())
            .claim("nonce_supported", true);
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

    private static String first(Map<String, List<String>> form, String name) {
        return form.getOrDefault(name, List.of()).stream().findFirst().orElse(null);
    }

    private static Map<String, List<String>> form(String body) {
        Map<String, List<String>> form = new LinkedHashMap<>();
        Stream.of(body.split("&"))
            .map(pair -> (pair + "=").split("=", 3))
            .forEach(pair -> form.computeIfAbsent(decode(pair[0]), name -> new ArrayList<>()).add(decode(pair[1])));
        return form;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    record RevokeRequest(
        String token,
        String tokenTypeHint,
        String clientId,
        boolean clientSecretValid
    ) {
    }

    /** 같은 이름이 두 번 실렸는지 알 수 있도록 값을 모두 보관한다. */
    record TokenRequest(Map<String, List<String>> form, String authorizationHeader) {
        String single(String name) {
            return form.getOrDefault(name, List.of()).stream().findFirst().orElse(null);
        }
    }

    private record Grant(String nonce, Consumer<JWTClaimsSet.Builder> mutation, boolean signWithOtherKey) {
    }
}
