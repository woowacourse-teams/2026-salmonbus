package com.gustler.backend.worker;

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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("observability")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestContainer.class)
class PrometheusEndpointTest {

    @LocalServerPort
    private int port;

    @Autowired
    private DataSource dataSource;

    @Test
    void worker의_루프백_엔드포인트에서_JVM과_실제_DB풀을_조회한다() throws Exception {
        com.gustler.backend.diagnostics.WorkerOperationLog.run("monitoring_probe", "all", () -> { });
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.isValid(2)).isTrue();
            HttpClient http = HttpClient.newHttpClient();
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/prometheus"))
                        .timeout(Duration.ofSeconds(5)).header("Accept", "text/plain").GET().build(),
                    HttpResponse.BodyHandlers.ofString());

                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.body()).contains("application=\"backend-worker\"", "jvm_memory_used_bytes{",
                    "hikaricp_connections_pending{", "hikaricp_connections_acquire_seconds_count{", "salmonbus_monitoring_heartbeat_timestamp{", "salmonbus_worker_operation_seconds_bucket{")
                    .doesNotContain("fake-service-key-for-test");
                assertThat(response.body().lines().filter(line -> line.startsWith("hikaricp_connections_active{")))
                    .anyMatch(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)) >= 1);
            });
        }
    }
}
