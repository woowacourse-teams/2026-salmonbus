package com.gustler.backend.api.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.gustler.backend.support.PostgresTestContainer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("observability")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
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

    @Test
    void DB에서_API_연결을_구분할_이름을_설정한다() throws Exception {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery("SHOW application_name")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("salmonbus-api");
        }
    }

    @Test
    void 지표는_관리_포트에만_있고_공개_readyz는_유지된다() throws Exception {
        assertThat(managementPort).isNotEqualTo(publicPort);
        assertThat(get(publicPort, "/actuator/prometheus").statusCode()).isEqualTo(404);
        assertThat(get(publicPort, "/readyz").statusCode()).isEqualTo(200);
        assertThat(get(managementPort, "/actuator/prometheus").statusCode()).isEqualTo(200);
    }

    @Test
    void 실제_API_요청을_경로별_히스토그램에_기록하고_요청값은_라벨에_싣지_않는다() throws Exception {
        assertThat(get(publicPort, "/api/v1/routes?probe=private-query-value").statusCode()).isEqualTo(200);

        String metrics = get(managementPort, "/actuator/prometheus").body();
        assertThat(metrics.lines().filter(line -> line.startsWith("http_server_requests_seconds_count{")))
            .anyMatch(line -> line.contains("uri=\"/api/v1/routes\"")
                && line.contains("status=\"200\"") && line.contains("application=\"backend-api\""));
        assertThat(metrics.lines().filter(line -> line.startsWith("http_server_requests_seconds_bucket{")))
            .anyMatch(line -> line.contains("uri=\"/api/v1/routes\"") && line.contains("le=\"0.5\""));
        assertThat(metrics).doesNotContain("private-query-value", "monitoring-test-request-id");
    }

    @Test
    void JVM과_실제_커넥션_풀의_사용량을_노출한다() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
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

    private HttpResponse<String> get(int port, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(5))
            .header("Accept", path.equals("/actuator/prometheus") ? "text/plain" : "application/json")
            .header("X-Request-Id", "monitoring-test-request-id")
            .GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
