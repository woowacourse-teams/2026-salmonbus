package com.gustler.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

class RuntimeProcessSeparationTest {

    private static final String POSTGRES_IMAGE = "postgres:18";
    private static final String API_JAR = "api.boot.jar";
    private static final String WORKER_JAR = "worker.boot.jar";
    private static final String SERVICE_KEY = "fake-service-key-for-test";
    private static final String CLIENT_API_PATH = "/api/v1/routes";
    private static final String HEALTH_PATH = "/actuator/health";
    private static final Duration BOOT_TIMEOUT = Duration.ofMinutes(3);
    private static final Duration POLL = Duration.ofMillis(500);
    private static final int LOG_TAIL = 4000;

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build();

    private static PostgreSQLContainer<?> postgres;
    private static Process api;
    private static Process worker;
    private static int apiPort;
    private static int workerPort;

    @BeforeAll
    static void 두_프로세스를_순서대로_띄운다() throws Exception {
        postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE);
        postgres.start();

        apiPort = freePort();
        api = start(API_JAR, apiPort, Map.of(), Files.createTempFile("salmonbus-api", ".log"));
        awaitHealthy(api, apiPort);

        workerPort = freePort();
        worker = start(WORKER_JAR, workerPort,
            Map.of("GBIS_SERVICE_KEY", SERVICE_KEY, "COLLECTION_ENABLED", "false"),
            Files.createTempFile("salmonbus-worker", ".log"));
        awaitHealthy(worker, workerPort);
    }

    @AfterAll
    static void 띄운_것을_모두_내린다() {
        destroy(api);
        destroy(worker);
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void 서로_다른_JAR_두_프로세스가_서로_다른_포트에_같이_떠_있다() {
        assertThat(api.isAlive()).isTrue();
        assertThat(worker.isAlive()).isTrue();
        assertThat(apiPort).isNotEqualTo(workerPort);
        assertThat(statusOf(apiPort, HEALTH_PATH)).isEqualTo(200);
        assertThat(statusOf(workerPort, HEALTH_PATH)).isEqualTo(200);
    }

    @Test
    void 두_프로세스_모두_health_가_UP_이다() {
        assertThat(bodyOf(apiPort, HEALTH_PATH)).contains("\"status\":\"UP\"");
        assertThat(bodyOf(workerPort, HEALTH_PATH)).contains("\"status\":\"UP\"");
    }

    @Test
    void api_는_클라이언트_API_를_내준다() {
        assertThat(statusOf(apiPort, CLIENT_API_PATH)).isEqualTo(200);
    }

    @Test
    void worker_는_클라이언트_API_를_안_내준다() {
        assertThat(statusOf(workerPort, CLIENT_API_PATH)).isEqualTo(404);
    }

    @Test
    void 스키마를_옮긴_쪽은_api_다() throws SQLException {
        assertThat(worker.isAlive()).isTrue();
        assertThat(appliedMigrationCount()).isPositive();
    }

    @Test
    void worker_는_수집을_켠_채_키가_없으면_못_뜬다() throws Exception {
        Path log = Files.createTempFile("salmonbus-worker-nokey", ".log");
        Process nokey = start(WORKER_JAR, freePort(), Map.of("COLLECTION_ENABLED", "true"), log);
        try {
            final boolean exited = nokey.waitFor(BOOT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);

            assertThat(exited).as("기동이 안 막히고 계속 떠 있다. 로그=%s", tailOf(log)).isTrue();
            assertThat(nokey.exitValue()).isNotZero();
            assertThat(tailOf(log)).contains("gbis.service-key");
        } finally {
            destroy(nokey);
        }
    }

    private static Process start(
        String jarProperty,
        int port,
        Map<String, String> extra,
        Path log
    ) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-jar",
            jarAt(jarProperty));
        Map<String, String> environment = builder.environment();
        environment.remove("GBIS_SERVICE_KEY");
        environment.put("SERVER_PORT", String.valueOf(port));
        environment.put("DB_URL", postgres.getJdbcUrl());
        environment.put("DB_USERNAME", postgres.getUsername());
        environment.put("DB_PASSWORD", postgres.getPassword());
        environment.putAll(extra);
        builder.redirectErrorStream(true);
        builder.redirectOutput(log.toFile());

        return builder.start();
    }

    private static void awaitHealthy(
        Process process,
        int port
    ) throws InterruptedException {
        final long deadline = System.nanoTime() + BOOT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                fail("기동 중에 죽었다. 종료코드=%d".formatted(process.exitValue()));
            }
            if (statusOf(port, HEALTH_PATH) == 200) {
                return;
            }
            Thread.sleep(POLL.toMillis());
        }
        fail("%s 안에 health 가 안 떴다".formatted(BOOT_TIMEOUT));
    }

    private static int statusOf(
        int port,
        String path
    ) {
        try {
            return HTTP.send(request(port, path), HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String bodyOf(
        int port,
        String path
    ) {
        try {
            return HTTP.send(request(port, path), HttpResponse.BodyHandlers.ofString()).body();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static HttpRequest request(
        int port,
        String path
    ) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();
    }

    private static int appliedMigrationCount() throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("select count(*) from flyway_schema_history")) {
            rows.next();

            return rows.getInt(1);
        }
    }

    private static String jarAt(
        String property
    ) {
        String path = System.getProperty(property);
        if (path == null || !Files.isRegularFile(Path.of(path))) {
            throw new IllegalStateException(
                "bootJar 를 못 찾았다(-D" + property + "). 이 테스트는 ./gradlew test 로 돌린다");
        }

        return path;
    }

    private static String tailOf(
        Path log
    ) {
        try {
            String whole = Files.readString(log);

            return whole.length() <= LOG_TAIL ? whole : whole.substring(whole.length() - LOG_TAIL);
        } catch (IOException e) {
            return "로그를 못 읽었다: " + e.getMessage();
        }
    }

    private static void destroy(
        Process process
    ) {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
