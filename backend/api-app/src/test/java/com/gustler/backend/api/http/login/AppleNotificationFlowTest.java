package com.gustler.backend.api.http.login;

import static com.gustler.backend.api.http.login.LoginBrowser.location;
import static com.gustler.backend.api.http.login.LoginBrowser.stateOf;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jwt.JWTClaimsSet;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/**
 * 애플 서버 간 알림을 실제 톰캣과 테스트용 애플 서버로 검증한다. 실제 애플은 부르지 않는다.
 *
 * <p>회원은 애플 로그인 흐름으로 가입해 리프레시 토큰을 가진 상태에서 시작한다. 알림은 테스트용 애플 키로 서명해 애플처럼
 * 쿠키와 CSRF 토큰 없이 보낸다.
 */
@ExtendWith(OutputCaptureExtension.class)
@LoginIntegrationTest
class AppleNotificationFlowTest {
    private static final String PATH = "/api/auth/apple/notifications";
    private static final String SUBJECT = "001234.apple.member";
    private static final String OTHER_SUBJECT = "009999.apple.someone";
    private static final String EMAIL = "relay-1234@privaterelay.appleid.com";

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private FakeAppleServer apple;

    @BeforeEach
    void 상태를_비운다() {
        apple.reset();
        jdbcClient.sql("DELETE FROM member").update();
        jdbcClient.sql("DELETE FROM provider_unlink").update();
        jdbcClient.sql("DELETE FROM SPRING_SESSION").update();
    }

    @Test
    void 연결을_끊은_뒤_다시_로그인한_기기는_늦게_온_연결_해제_알림에도_로그인이_이어진다() throws Exception {
        // given
        LoginBrowser before = signedIn(SUBJECT);
        Instant revokedAt = startedAtOfOnlyFamily().plusMillis(1);
        LoginBrowser after = signedIn(SUBJECT);

        // when
        HttpResponse<String> actual = send(notification("consent-revoked", SUBJECT, revokedAt));
        HttpResponse<String> replayed = send(notification("consent-revoked", SUBJECT, revokedAt));

        // then
        HttpResponse<String> beforeRefresh = before.postJson("/api/auth/refresh", "{}");
        HttpResponse<String> afterRefresh = after.postJson("/api/auth/refresh", "{}");
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.OK.value());
            softly.assertThat(replayed.statusCode()).isEqualTo(HttpStatus.OK.value());
            softly.assertThat(beforeRefresh.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            softly.assertThat(afterRefresh.statusCode()).isEqualTo(HttpStatus.NO_CONTENT.value());
            softly.assertThat(count("refresh_token")).isEqualTo(1);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"account-deleted", "account-delete"})
    void 애플_계정이_지워졌다는_알림이면_회원을_탈퇴시키고_애플_토큰은_취소하지_않는다(String type) throws Exception {
        // given
        signedIn(SUBJECT);
        double before = counted("account_deleted", "applied");

        // when
        HttpResponse<String> actual = send(notification(type, SUBJECT));

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.OK.value());
            softly.assertThat(actual.body()).isEmpty();
            softly.assertThat(actual.headers().allValues("Set-Cookie")).isEmpty();
            softly.assertThat(count("member")).isZero();
            softly.assertThat(count("refresh_token")).isZero();
            softly.assertThat(count("provider_unlink")).as("애플 연결 해제 요청을 남기지 않는다").isZero();
            softly.assertThat(count("SPRING_SESSION")).as("임시 세션을 만들지 않는다").isZero();
            softly.assertThat(apple.revokeRequests()).isEmpty();
            softly.assertThat(counted("account_deleted", "applied") - before).isEqualTo(1);
        });
    }

    @Test
    void 연결을_끊었다는_알림이면_모든_기기를_로그아웃시키고_회원은_남긴다() throws Exception {
        // given
        LoginBrowser phone = signedIn(SUBJECT);
        LoginBrowser laptop = signedIn(SUBJECT);
        double before = counted("consent_revoked", "applied");

        // when
        HttpResponse<String> actual = send(notification("consent-revoked", SUBJECT));

        // then
        HttpResponse<String> phoneRefresh = phone.postJson("/api/auth/refresh", "{}");
        HttpResponse<String> laptopRefresh = laptop.postJson("/api/auth/refresh", "{}");
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.OK.value());
            softly.assertThat(count("member")).isEqualTo(1);
            softly.assertThat(count("refresh_token")).isZero();
            softly.assertThat(phoneRefresh.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            softly.assertThat(laptopRefresh.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            softly.assertThat(apple.revokeRequests()).isEmpty();
            softly.assertThat(counted("consent_revoked", "applied") - before).isEqualTo(1);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"email-enabled", "email-disabled", "unknown-type"})
    void 이메일_전달_알림이나_모르는_종류의_알림은_아무것도_바꾸지_않는다(String type) throws Exception {
        // given
        LoginBrowser browser = signedIn(SUBJECT);
        double before = counted("ignored", "ignored");

        // when
        HttpResponse<String> actual = send(notification(type, SUBJECT));

        // then
        HttpResponse<String> refreshed = browser.postJson("/api/auth/refresh", "{}");
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.OK.value());
            softly.assertThat(count("member")).isEqualTo(1);
            softly.assertThat(refreshed.statusCode()).as("로그인이 이어진다").isEqualTo(HttpStatus.NO_CONTENT.value());
            softly.assertThat(counted("ignored", "ignored") - before).isEqualTo(1);
        });
    }

    @Test
    void 회원이_없는_계정의_알림도_200이고_다른_회원은_그대로다() throws Exception {
        // given
        signedIn(SUBJECT);
        double before = counted("account_deleted", "no_member");

        // when
        HttpResponse<String> actual = send(notification("account-deleted", OTHER_SUBJECT));

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.OK.value());
            softly.assertThat(count("member")).isEqualTo(1);
            softly.assertThat(count("refresh_token")).isEqualTo(1);
            softly.assertThat(counted("account_deleted", "no_member") - before).isEqualTo(1);
        });
    }

    @Test
    void 같은_계정_삭제_알림을_두_번_받아도_둘_다_200이고_두_번째는_회원이_없는_알림이다() throws Exception {
        // given
        signedIn(SUBJECT);
        String notification = notification("account-deleted", SUBJECT);
        double appliedBefore = counted("account_deleted", "applied");
        double noMemberBefore = counted("account_deleted", "no_member");

        // when
        HttpResponse<String> first = send(notification);
        HttpResponse<String> second = send(notification);

        // then
        assertSoftly(softly -> {
            softly.assertThat(first.statusCode()).isEqualTo(HttpStatus.OK.value());
            softly.assertThat(second.statusCode()).isEqualTo(HttpStatus.OK.value());
            softly.assertThat(count("member")).isZero();
            softly.assertThat(counted("account_deleted", "applied") - appliedBefore).isEqualTo(1);
            softly.assertThat(counted("account_deleted", "no_member") - noMemberBefore).isEqualTo(1);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {FakeAppleServer.CLIENT_ID, FakeAppleServer.APP_CLIENT_ID})
    void 대상이_웹_Services_ID든_앱_번들_ID든_받는다(String audience) throws Exception {
        // given
        signedIn(SUBJECT);

        // when
        HttpResponse<String> actual = send(apple.notification(
            withEvents("account-deleted", SUBJECT).andThen(claims -> claims.audience(audience)), false));

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.OK.value());
            softly.assertThat(count("member")).isZero();
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void events가_JSON_문자열이어도_객체여도_읽는다(boolean asJsonString) throws Exception {
        // given
        signedIn(SUBJECT);
        Map<String, Object> events = events("account-deleted", SUBJECT);
        Object claim = asJsonString ? objectMapper.writeValueAsString(events) : events;

        // when
        HttpResponse<String> actual = send(apple.notification(claims -> claims.claim("events", claim), false));

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.OK.value());
            softly.assertThat(count("member")).isZero();
        });
    }

    static Stream<Arguments> 검증하지_못한_알림() {
        Instant hourAgo = Instant.now().minusSeconds(3600);
        return Stream.of(
            args("애플 공개키와 맞지 않는 서명", claims -> { }, true),
            args("다른 발급자", claims -> claims.issuer("https://evil.example"), false),
            args("다른 대상", claims -> claims.audience("com.example.other"), false),
            args("만료", claims -> claims.issueTime(Date.from(hourAgo.minusSeconds(3600)))
                .expirationTime(Date.from(hourAgo)), false),
            args("events 없음", claims -> claims.claim("events", null), false),
            args("events 에 sub 없음", claims -> claims.claim("events", Map.of("type", "account-deleted")), false),
            args("events 가 JSON 이 아님", claims -> claims.claim("events", "account-deleted"), false));
    }

    @ParameterizedTest
    @MethodSource("검증하지_못한_알림")
    void 검증하지_못한_알림은_400이고_아무것도_바꾸지_않는다(
        String name,
        Consumer<JWTClaimsSet.Builder> mutation,
        boolean otherKey
    ) throws Exception {
        // given
        signedIn(SUBJECT);
        double before = counted("unknown", "rejected");

        // when
        HttpResponse<String> actual = send(apple.notification(
            withEvents("account-deleted", SUBJECT).andThen(mutation), otherKey));

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).as(name).isEqualTo(HttpStatus.BAD_REQUEST.value());
            softly.assertThat(code(actual)).as(name).isEqualTo("INVALID_REQUEST");
            softly.assertThat(count("member")).as(name).isEqualTo(1);
            softly.assertThat(count("refresh_token")).as(name).isEqualTo(1);
            softly.assertThat(counted("unknown", "rejected") - before).as(name).isEqualTo(1);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"payload\":\"\"}", "{\"payload\":\"not-a-jwt\"}"})
    void 본문에_서명된_JWT가_없으면_400이고_아무것도_바꾸지_않는다(String body) throws Exception {
        // given
        signedIn(SUBJECT);

        // when
        HttpResponse<String> actual = post(body);

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
            softly.assertThat(code(actual)).isEqualTo("INVALID_REQUEST");
            softly.assertThat(count("member")).isEqualTo(1);
        });
    }

    @Test
    void 알림을_받아도_로그에_JWT와_애플_계정_식별자와_이메일이_남지_않는다(CapturedOutput output) throws Exception {
        // given
        signedIn(SUBJECT);
        String jwtId = "notification-jwt-id";
        Map<String, Object> emailEvents = Map.of(
            "type", "email-disabled", "sub", SUBJECT, "email", EMAIL, "is_private_email", "true");
        String emailDisabled = apple.notification(
            claims -> claims.jwtID(jwtId).claim("events", objectMapper.writeValueAsString(emailEvents)), false);
        String accountDeleted = notification("account-deleted", SUBJECT);
        String forged = apple.notification(withEvents("account-deleted", SUBJECT), true);

        // when
        send(emailDisabled);
        send(forged);
        send(accountDeleted);

        // then
        assertSoftly(softly -> {
            softly.assertThat(output.getAll()).as("처리 결과는 남는다")
                .contains("type=email-disabled", "reason=token", "event=account_deleted result=applied");
            softly.assertThat(output.getAll())
                .doesNotContain(emailDisabled, accountDeleted, forged, SUBJECT, jwtId, EMAIL);
        });
    }

    private static Arguments args(String name, Consumer<JWTClaimsSet.Builder> mutation, boolean otherKey) {
        return Arguments.of(name, mutation, otherKey);
    }

    private static Map<String, Object> events(String type, String subject) {
        return events(type, subject, Instant.now());
    }

    private static Map<String, Object> events(String type, String subject, Instant occurredAt) {
        return Map.of("type", type, "sub", subject, "event_time", occurredAt.toEpochMilli());
    }

    /** 실제 알림처럼 {@code events} 를 JSON 문자열로 넣는다. */
    private Consumer<JWTClaimsSet.Builder> withEvents(String type, String subject) {
        return withEvents(events(type, subject));
    }

    private Consumer<JWTClaimsSet.Builder> withEvents(Map<String, Object> events) {
        String json = objectMapper.writeValueAsString(events);
        return claims -> claims.claim("events", json);
    }

    private String notification(String type, String subject) throws JOSEException {
        return apple.notification(withEvents(type, subject), false);
    }

    private String notification(String type, String subject, Instant occurredAt) throws JOSEException {
        return apple.notification(withEvents(events(type, subject, occurredAt)), false);
    }

    private HttpResponse<String> send(String payload) throws IOException, InterruptedException {
        return post(objectMapper.writeValueAsString(Map.of("payload", payload)));
    }

    private HttpResponse<String> post(String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + PATH))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private LoginBrowser signedIn(String subject) throws IOException, InterruptedException {
        LoginBrowser browser = new LoginBrowser(port);
        String authorization = location(browser.get("/api/auth/apple/start"));
        browser.get("/api/auth/apple/callback?code=" + apple.authorize(authorization, subject)
            + "&state=" + stateOf(authorization));
        apple.reset();
        return browser;
    }

    private String code(HttpResponse<String> response) {
        return objectMapper.readTree(response.body()).path("code").asString();
    }

    private Instant startedAtOfOnlyFamily() {
        return jdbcClient.sql("SELECT started_at FROM refresh_token")
            .query(OffsetDateTime.class)
            .single()
            .toInstant();
    }

    private long count(String table) {
        return jdbcClient.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private double counted(String event, String result) {
        return meters.counter("auth.notification", "provider", "APPLE", "event", event, "result", result).count();
    }
}
