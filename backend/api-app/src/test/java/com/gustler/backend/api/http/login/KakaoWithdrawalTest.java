package com.gustler.backend.api.http.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.api.account.token.AccessTokens;
import com.gustler.backend.api.account.unlink.ProviderUnlinkRetryJob;
import com.gustler.backend.member.api.LoginProvider;
import com.gustler.backend.member.api.RefreshTokens;
import com.gustler.backend.member.api.SignInWithSocialAccount;
import com.gustler.backend.member.api.SocialIdentity;
import com.gustler.backend.support.PostgresTestContainer;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

/**
 * 카카오 회원의 탈퇴({@code DELETE /api/me})와 카카오 연결 해제를 실제 톰캣과 테스트용 카카오 서버로 검증한다.
 *
 * <p>로그인 흐름은 {@code KakaoLoginFlowTest} 가 검증하므로 여기서는 회원과 토큰을 직접 만든다.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(PostgresTestContainer.class)
class KakaoWithdrawalTest {
    private static final String ACCESS_COOKIE = "__Host-sb_at";
    private static final String REFRESH_COOKIE = "__Host-sb_rt";
    private static final String CSRF_COOKIE = "__Host-XSRF-TOKEN";
    private static final String SUBJECT = "4211234567";

    private static FakeKakaoServer kakao;

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private SignInWithSocialAccount signIn;

    @Autowired
    private AccessTokens accessTokens;

    @Autowired
    private RefreshTokens refreshTokens;

    @Autowired
    private ProviderUnlinkRetryJob retryJob;

    @Autowired
    private MeterRegistry meters;

    private UUID memberId;
    private String refreshToken;
    private String csrfToken;

    @BeforeAll
    static void 카카오_서버를_띄운다() throws Exception {
        if (kakao == null) {
            kakao = new FakeKakaoServer();
        }
    }

    @AfterAll
    static void 카카오_서버를_내린다() {
        kakao.close();
    }

    @DynamicPropertySource
    static void 로그인_설정(DynamicPropertyRegistry registry) throws Exception {
        if (kakao == null) {
            kakao = new FakeKakaoServer();
        }
        registry.add("auth.enabled", () -> "true");
        registry.add("auth.public-base-url", () -> "https://www.salmonbus.test");
        registry.add("auth.kakao.enabled", () -> "true");
        registry.add("auth.kakao.client-id", () -> FakeKakaoServer.CLIENT_ID);
        registry.add("auth.kakao.client-secret", () -> FakeKakaoServer.CLIENT_SECRET);
        registry.add("auth.kakao.issuer", kakao::baseUrl);
        registry.add("auth.kakao.authorization-uri", () -> kakao.baseUrl() + "/oauth/authorize");
        registry.add("auth.kakao.token-uri", () -> kakao.baseUrl() + "/oauth/token");
        registry.add("auth.kakao.jwk-set-uri", () -> kakao.baseUrl() + "/jwks.json");
        registry.add("auth.kakao.unlink-uri", () -> kakao.baseUrl() + "/v1/user/unlink");
    }

    @BeforeEach
    void 회원과_토큰을_만든다() throws Exception {
        kakao.reset();
        jdbcClient.sql("DELETE FROM member").update();
        jdbcClient.sql("DELETE FROM provider_unlink").update();
        memberId = signIn.signIn(new SocialIdentity(LoginProvider.KAKAO, SUBJECT)).publicId();
        refreshToken = refreshTokens.issue(memberId).token();
        csrfToken = csrfToken();
    }

    @Test
    void 탈퇴하면_회원과_로그인을_지우고_카카오_연결을_끊고_인증_쿠키를_지운다() throws Exception {
        // when
        HttpResponse<String> actual = withdraw(accessTokens.issue(memberId));

        // then
        List<FakeKakaoServer.TokenRequest> unlinks = kakao.unlinkRequests();
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.NO_CONTENT.value());
            softly.assertThat(header(actual, "Cache-Control")).isEqualTo("no-store");
            softly.assertThat(setCookie(actual, ACCESS_COOKIE)).contains("Max-Age=0");
            softly.assertThat(setCookie(actual, REFRESH_COOKIE)).contains("Max-Age=0");
            softly.assertThat(count("member")).isZero();
            softly.assertThat(count("member_identity")).isZero();
            softly.assertThat(count("refresh_token")).isZero();
            softly.assertThat(count("provider_unlink")).as("해제에 성공하면 요청을 남기지 않는다").isZero();
            softly.assertThat(unlinks).hasSize(1);
            softly.assertThat(unlinks.get(0).authorizationHeader()).isEqualTo("KakaoAK test-only-kakao-admin-key");
            softly.assertThat(unlinks.get(0).form()).containsEntry("target_id_type", "user_id")
                .containsEntry("target_id", SUBJECT);
        });
    }

    @Test
    void 탈퇴한_뒤에는_남은_액세스_토큰과_리프레시_토큰으로_아무것도_할_수_없다() throws Exception {
        // given
        String accessToken = accessTokens.issue(memberId);
        withdraw(accessToken);

        // when
        HttpResponse<String> me = send(request("/api/me").header("Cookie", ACCESS_COOKIE + "=" + accessToken).GET());
        HttpResponse<String> refreshed = send(request("/api/auth/refresh")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"refreshToken\":\"" + refreshToken + "\"}")));
        HttpResponse<String> withdrawnAgain = withdraw(accessToken);
        HttpResponse<String> appleTicket = send(request("/api/me/withdrawal/apple")
            .header("Authorization", "Bearer " + accessToken)
            .POST(HttpRequest.BodyPublishers.noBody()));

        // then
        assertSoftly(softly -> {
            softly.assertThat(me.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            softly.assertThat(refreshed.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            softly.assertThat(withdrawnAgain.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            softly.assertThat(code(withdrawnAgain)).isEqualTo("UNAUTHENTICATED");
            softly.assertThat(appleTicket.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            softly.assertThat(code(appleTicket)).isEqualTo("UNAUTHENTICATED");
            softly.assertThat(kakao.unlinkRequests()).as("두 번째 탈퇴 요청은 카카오를 부르지 않는다").hasSize(1);
        });
    }

    @Test
    void 같은_카카오_계정으로_다시_오면_새_회원이다() throws Exception {
        // given
        withdraw(accessTokens.issue(memberId));

        // when
        UUID again = signIn.signIn(new SocialIdentity(LoginProvider.KAKAO, SUBJECT)).publicId();

        // then
        assertSoftly(softly -> {
            softly.assertThat(again).isNotEqualTo(memberId);
            softly.assertThat(count("member")).isEqualTo(1);
        });
    }

    @Test
    void 카카오가_해제에_실패해도_탈퇴는_끝나고_요청을_남겼다가_다시_시도한다() throws Exception {
        // given
        kakao.answerUnlinkWith(HttpStatus.INTERNAL_SERVER_ERROR.value(), "{\"msg\":\"internal\",\"code\":-1}");

        // when
        HttpResponse<String> actual = withdraw(accessTokens.issue(memberId));

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.NO_CONTENT.value());
            softly.assertThat(count("member")).isZero();
            softly.assertThat(longOf("SELECT attempts FROM provider_unlink WHERE status = 'PENDING'")).isEqualTo(1);
        });

        // when
        kakao.answerUnlinkWith(HttpStatus.OK.value(), "{\"id\":4211234567}");
        jdbcClient.sql("UPDATE provider_unlink SET next_attempt_at = now() - interval '1 minute'").update();
        retryJob.retry();

        // then
        assertSoftly(softly -> {
            softly.assertThat(count("provider_unlink")).as("다시 시도해 성공하면 요청과 식별자를 지운다").isZero();
            softly.assertThat(kakao.unlinkRequests()).hasSize(2);
        });
    }

    @Test
    void 카카오가_이미_연결되지_않은_사용자라고_답하면_해제된_것으로_본다() throws Exception {
        // given
        kakao.answerUnlinkWith(HttpStatus.BAD_REQUEST.value(), "{\"msg\":\"NotRegistered\",\"code\":-101}");
        final double notLinkedBefore = notLinkedCount();

        // when
        HttpResponse<String> actual = withdraw(accessTokens.issue(memberId));

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.NO_CONTENT.value());
            softly.assertThat(count("provider_unlink")).isZero();
            softly.assertThat(notLinkedCount()).as("키 오설정을 알아챌 수 있게 따로 센다").isEqualTo(notLinkedBefore + 1);
        });
    }

    @Test
    void 애플_회원은_다시_인증하라고_409로_답하고_지우지_않는다() throws Exception {
        // given
        UUID appleMember = signIn.signIn(new SocialIdentity(LoginProvider.APPLE, "001.apple")).publicId();

        // when
        HttpResponse<String> actual = withdraw(accessTokens.issue(appleMember));

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
            softly.assertThat(code(actual)).isEqualTo("REAUTH_REQUIRED");
            softly.assertThat(setCookie(actual, ACCESS_COOKIE)).isEmpty();
            softly.assertThat(count("member")).isEqualTo(2);
            softly.assertThat(kakao.unlinkRequests()).isEmpty();
        });
    }

    @Test
    void 카카오_회원은_애플_탈퇴_티켓을_받을_수_없다() throws Exception {
        // when
        HttpResponse<String> actual = send(request("/api/me/withdrawal/apple")
            .header("Authorization", "Bearer " + accessTokens.issue(memberId))
            .POST(HttpRequest.BodyPublishers.noBody()));

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
            softly.assertThat(code(actual)).isEqualTo("INVALID_REQUEST");
        });
    }

    @Test
    void CSRF_토큰_없이_쿠키로_탈퇴하려_하면_400이고_지우지_않는다() throws Exception {
        // when
        HttpResponse<String> actual = send(request("/api/me")
            .header("Cookie", ACCESS_COOKIE + "=" + accessTokens.issue(memberId))
            .DELETE());

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
            softly.assertThat(code(actual)).isEqualTo("CSRF_REJECTED");
            softly.assertThat(count("member")).isEqualTo(1);
        });
    }

    @Test
    void Authorization_헤더로_탈퇴하면_CSRF_토큰이_필요_없다() throws Exception {
        // when
        HttpResponse<String> actual = send(request("/api/me")
            .header("Authorization", "Bearer " + accessTokens.issue(memberId))
            .DELETE());

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.NO_CONTENT.value());
            softly.assertThat(count("member")).isZero();
        });
    }

    @Test
    void 로그인하지_않았으면_401이다() throws Exception {
        // when
        HttpResponse<String> actual = send(request("/api/me")
            .header("Cookie", CSRF_COOKIE + "=" + csrfToken)
            .header("X-XSRF-TOKEN", csrfToken)
            .DELETE());

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            softly.assertThat(code(actual)).isEqualTo("UNAUTHENTICATED");
            softly.assertThat(count("member")).isEqualTo(1);
        });
    }

    private HttpResponse<String> withdraw(String accessToken) throws IOException, InterruptedException {
        return send(request("/api/me")
            .header("Cookie", ACCESS_COOKIE + "=" + accessToken + "; " + CSRF_COOKIE + "=" + csrfToken)
            .header("X-XSRF-TOKEN", csrfToken)
            .DELETE());
    }

    private String csrfToken() throws IOException, InterruptedException {
        String cookie = setCookie(send(request("/api/auth/session").GET()), CSRF_COOKIE);
        return cookie.substring(CSRF_COOKIE.length() + 1).split(";", 2)[0];
    }

    private double notLinkedCount() {
        return meters.counter("auth.unlink", "provider", "KAKAO", "result", "not_linked").count();
    }

    private long count(String table) {
        return longOf("SELECT count(*) FROM " + table);
    }

    private long longOf(String sql) {
        return jdbcClient.sql(sql).query(Long.class).single();
    }

    private String code(HttpResponse<String> response) {
        return objectMapper.readTree(response.body()).path("code").asString();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) throws IOException, InterruptedException {
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String header(HttpResponse<String> response, String name) {
        return response.headers().firstValue(name).orElse("");
    }

    private static String setCookie(HttpResponse<String> response, String name) {
        return response.headers().allValues("Set-Cookie").stream()
            .filter(cookie -> cookie.startsWith(name + "="))
            .findFirst()
            .orElse("");
    }
}
