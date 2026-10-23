package com.gustler.backend.api.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.gustler.backend.support.PostgresTestContainer;
import com.gustler.backend.api.monitoring.UserMonitoring;
import com.gustler.backend.api.monitoring.UserMonitoring.LoginResult;
import com.gustler.backend.api.monitoring.UserMonitoring.MemberKind;
import com.gustler.backend.member.api.LoginProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
@ActiveProfiles("observability")
@Import(PostgresTestContainer.class)
class PrometheusEndpointTest {
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3)).build();

    @LocalServerPort
    private int publicPort;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private UserMonitoring userMonitoring;

    @Test
    void DB에서_API_연결을_구분할_이름을_설정한다() throws Exception {
        // when
        try (Connection connection = dataSource.getConnection();
            Statement statement = connection.createStatement();
            ResultSet result = statement.executeQuery("SHOW application_name")) {

            // then
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("salmonbus-api");
        }
    }

    @Test
    void 지표는_관리_포트에만_있고_공개_readyz는_유지된다() throws Exception {
        // given
        assertThat(managementPort).isNotEqualTo(publicPort);

        // when
        final int statusCode1 = get(publicPort, "/actuator/prometheus").statusCode();

        // then
        assertThat(statusCode1).isEqualTo(404);

        // when
        final int statusCode2 = get(publicPort, "/readyz").statusCode();

        // then
        assertThat(statusCode2).isEqualTo(200);

        // when
        final int statusCode3 = get(managementPort, "/actuator/prometheus").statusCode();

        // then
        assertThat(statusCode3).isEqualTo(200);
    }

    @Test
    void 실제_API_요청을_경로별_히스토그램에_기록하고_요청값은_라벨에_싣지_않는다() throws Exception {
        // when
        final int statusCode1 = get(publicPort, "/api/v1/routes?probe=private-query-value").statusCode();

        // then
        assertThat(statusCode1).isEqualTo(200);

        // when
        String metrics = get(managementPort, "/actuator/prometheus").body();

        // then
        assertThat(metrics.lines().filter(line -> line.startsWith("http_server_requests_seconds_count{")))
            .anyMatch(line -> line.contains("uri=\"/api/v1/routes\"")
                && line.contains("status=\"200\"") && line.contains("application=\"backend-api\""));
        assertThat(metrics.lines().filter(line -> line.startsWith("http_server_requests_seconds_bucket{")))
            .anyMatch(line -> line.contains("uri=\"/api/v1/routes\"") && line.contains("le=\"0.5\""));
        assertThat(metrics).doesNotContain("private-query-value", "monitoring-test-request-id");
    }

    @Test
    void JVM과_실제_커넥션_풀의_사용량을_노출한다() throws Exception {
        // when
        try (Connection connection = dataSource.getConnection()) {

            // then
            assertThat(connection.isValid(2)).isTrue();
            // Hikari의 풀 통계는 매 호출마다 갱신되지 않으므로 실제 사용량 반영을 기다린다.
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                String metrics = get(managementPort, "/actuator/prometheus").body();
                assertThat(metrics).contains("jvm_memory_used_bytes{", "hikaricp_connections_pending{",
                    "hikaricp_connections_acquire_seconds_count{", "application=\"backend-api\"");
                assertThat(metrics.lines().filter(line -> line.startsWith("hikaricp_connections_active{")))
                    .anyMatch(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)) >= 1);
            });
        }
    }

    @Test
    void 사용자_지표는_관리_포트에만_노출하고_사용자_식별값을_싣지_않는다() throws Exception {
        // given
        userMonitoring.refreshCurrentMembers();
        userMonitoring.succeeded(LoginProvider.KAKAO);
        userMonitoring.login(LoginProvider.KAKAO, LoginResult.SUCCESS, MemberKind.NEW);

        // when
        String metrics = get(managementPort, "/actuator/prometheus").body();

        // then
        assertThat(metrics.lines().filter(line -> line.startsWith("salmonbus_users_current{")))
            .anyMatch(line -> line.contains("application=\"backend-api\""));
        assertThat(metrics.lines().filter(line -> line.startsWith("salmonbus_users_snapshot_timestamp{")))
            .anyMatch(line -> line.contains("application=\"backend-api\""));
        assertThat(metrics.lines().filter(line -> line.startsWith("salmonbus_user_registration_total{")))
            .anyMatch(line -> line.contains("provider=\"kakao\"") && line.contains("result=\"success\""));
        assertThat(metrics.lines().filter(line -> line.startsWith("salmonbus_user_login_total{")))
            .anyMatch(line -> line.contains("provider=\"kakao\"") && line.contains("result=\"success\"")
                && line.contains("member=\"new\""));
        assertThat(metrics).doesNotContain("public_id", "email", "token", "subject");
        assertThat(get(publicPort, "/actuator/prometheus").statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> get(final int port, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", expectedContentType(path))
                .header("X-Request-Id", "monitoring-test-request-id")
                .GET().build(), BodyHandlers.ofString());
    }

    private static String expectedContentType(String path) {
        if (path.equals("/actuator/prometheus")) {
            return "text/plain";
        }
        return "application/json";
    }
}
