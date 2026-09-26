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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

class RuntimeProcessSeparationTest {

    private static final String POSTGRES_IMAGE = "postgres:18";
    private static final String API_JAR = "api.boot.jar";
    private static final String WORKER_JAR = "worker.boot.jar";
    private static final String CLIENT_API_PATH = "/api/v1/routes";
    private static final String HEALTH_PATH = "/actuator/health";
    private static final String READYZ_PATH = "/readyz";
    private static final String SCHEDULED_TASKS_PATH = "/actuator/scheduledtasks";
    private static final String DISABLED_WORKER_APPLICATION_NAME = "runtime-disabled-worker";
    private static final Duration BOOT_TIMEOUT = Duration.ofMinutes(3);
    private static final Duration POLL = Duration.ofMillis(500);
    private static final Duration DISABLED_JOB_OBSERVATION = Duration.ofSeconds(2);
    private static final Set<String> PROJECT_MODULES = Set.of(
        "api-app", "worker-app", "maintenance-app", "common", "route-catalog",
        "observations", "forecasting", "api-call-quota", "gbis-client"
    );
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
    static void api_가_빈_DB_를_V18_로_옮긴_다음_worker_를_띄운다() throws Exception {
        postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE);
        postgres.start();

        Path apiLog = Files.createTempFile("salmonbus-api", ".log");
        apiPort = freePort();
        api = start(API_JAR, apiPort, Map.of(), apiLog);
        awaitHealthy(api, apiPort, apiLog);
        assertFinalSchema();
        assertThat(statusOf(apiPort, CLIENT_API_PATH)).isEqualTo(200);
        assertThat(statusOf(apiPort, READYZ_PATH)).isEqualTo(200);

        // API가 만든 최종 스키마에 설치한다. Worker 기동 중 롤백된 쓰기도 기록한다.
        installWriteAudit();
        Path workerLog = Files.createTempFile("salmonbus-worker", ".log");
        workerPort = freePort();
        worker = start(WORKER_JAR, workerPort, Map.of(
            "DB_URL", postgres.getJdbcUrl() + (postgres.getJdbcUrl().contains("?") ? "&" : "?")
                + "ApplicationName=" + DISABLED_WORKER_APPLICATION_NAME,
            "MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE", "health,info,scheduledtasks",
            "FORECAST_INTERVAL", "100ms",
            "FORECAST_SETTLEMENT_INTERVAL", "100ms",
            "FORECAST_STATISTICS_INTERVAL", "100ms",
            "FORECAST_QUALITY_INVESTIGATION_INTERVAL", "100ms"
        ), workerLog);
        awaitHealthy(worker, workerPort, workerLog);
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
    void api_는_서버_포트에_readyz_를_내준다() {
        assertThat(statusOf(apiPort, READYZ_PATH)).isEqualTo(200);
        assertThat(bodyOf(apiPort, READYZ_PATH)).contains("\"status\":\"UP\"");
    }

    @Test
    void api_가_적용한_V18_스키마에_기존_완료_평가_품질_컬럼이_없다() throws SQLException {
        assertFinalSchema();
    }

    @Test
    void 실행_JAR_에는_각_프로세스가_사용하는_프로젝트_라이브러리만_들어간다() throws IOException {
        assertThat(projectLibraries(API_JAR)).containsExactly("common");
        assertThat(projectLibraries(WORKER_JAR)).containsExactlyInAnyOrder(
            "common", "route-catalog", "observations", "forecasting", "api-call-quota", "gbis-client"
        );
    }

    @Test
    void 수집_예보_품질_조사를_끄면_worker_는_스케줄을_등록하거나_DB에_쓰지_않는다()
        throws SQLException, InterruptedException {
        assertDisabledWorkerConnection();
        assertNoScheduledTasks();
        assertNoWrites();

        // 예보 관련 간격은 100ms다. 등록된 스케줄 확인과 여러 실행 간격의 쓰기 감사를 함께 한다.
        Thread.sleep(DISABLED_JOB_OBSERVATION.toMillis());

        assertThat(worker.isAlive()).isTrue();
        assertThat(statusOf(workerPort, HEALTH_PATH)).isEqualTo(200);
        assertDisabledWorkerConnection();
        assertNoScheduledTasks();
        assertNoWrites();
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
        environment.keySet().removeIf(name -> name.startsWith("GBIS_")
            || name.startsWith("MODEL_") || name.startsWith("COLLECTION_")
            || name.startsWith("FORECAST_") || name.startsWith("MANAGEMENT_")
            || name.equals("SPRING_APPLICATION_JSON"));
        environment.put("SERVER_PORT", String.valueOf(port));
        environment.put("DB_URL", postgres.getJdbcUrl());
        environment.put("DB_USERNAME", postgres.getUsername());
        environment.put("DB_PASSWORD", postgres.getPassword());
        environment.put("COLLECTION_ENABLED", "false");
        environment.put("FORECAST_ENABLED", "false");
        environment.put("FORECAST_QUALITY_ENABLED", "false");
        environment.putAll(extra);
        builder.redirectErrorStream(true);
        builder.redirectOutput(log.toFile());

        return builder.start();
    }

    private static void awaitHealthy(
        Process process,
        int port,
        Path log
    ) throws InterruptedException {
        final long deadline = System.nanoTime() + BOOT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                fail("기동 중에 죽었다. 종료코드=%d, 로그=%s".formatted(process.exitValue(), tailOf(log)));
            }
            if (statusOf(port, HEALTH_PATH) == 200) {
                return;
            }
            Thread.sleep(POLL.toMillis());
        }
        fail("%s 안에 health 가 안 떴다. 로그=%s".formatted(BOOT_TIMEOUT, tailOf(log)));
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

    private static void assertFinalSchema() throws SQLException {
        assertThat(queryStrings("""
            SELECT version FROM flyway_schema_history WHERE version = '18' AND success
            """)).containsExactly("18");
        assertThat(queryStrings("""
            SELECT table_name || '.' || column_name
            FROM information_schema.columns
            WHERE table_schema = 'public' AND (
                (table_name = 'observation_batch' AND column_name = 'forecast_completed_at')
                OR (table_name = 'seat_forecast'
                    AND column_name IN ('scoring_state', 'arrival_observation_id', 'seats_on_arrival', 'scored_at'))
                OR (table_name = 'route' AND column_name = 'quality_revision')
                OR (table_name = 'route_version'
                    AND column_name IN ('maximum_observation_gap_seconds', 'observation_gap_evidence'))
            )
            """)).as("V18에서 제거한 기존 저장 컬럼").isEmpty();
    }

    private static void installWriteAudit() throws SQLException {
        try (Connection connection = databaseConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SEQUENCE public.runtime_test_write_attempt");
            statement.execute("""
                CREATE FUNCTION public.record_runtime_test_write() RETURNS trigger LANGUAGE plpgsql AS $body$
                BEGIN
                    IF current_setting('application_name') = '%s' THEN
                        PERFORM nextval('public.runtime_test_write_attempt');
                    END IF;
                    RETURN NULL;
                END;
                $body$
                """.formatted(DISABLED_WORKER_APPLICATION_NAME));
            statement.execute("""
                DO $body$
                DECLARE audited_table text;
                BEGIN
                    FOR audited_table IN
                        SELECT tablename FROM pg_tables
                        WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'
                    LOOP
                        EXECUTE format(
                            'CREATE TRIGGER runtime_test_write_audit BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE'
                            ' ON public.%I FOR EACH STATEMENT EXECUTE FUNCTION public.record_runtime_test_write()',
                            audited_table);
                    END LOOP;
                END;
                $body$
                """);
        }
        assertThat(queryStrings("""
            SELECT DISTINCT event_object_table FROM information_schema.triggers
            WHERE trigger_schema = 'public' AND trigger_name = 'runtime_test_write_audit'
            """)).contains("observation_batch", "forecast_publication", "forecast_evaluation", "route",
                "route_data_quality", "trip_quality_rebuild", "daily_call_quota", "model_active_slot");
        assertNoWrites();
    }

    private static void assertNoWrites() throws SQLException {
        assertThat(queryStrings("""
            SELECT CASE WHEN is_called THEN 'writes=' || last_value ELSE 'no writes' END
            FROM public.runtime_test_write_attempt
            """)).as("Worker 기동 이후 커밋 또는 롤백된 쓰기 시도").containsExactly("no writes");
    }

    private static void assertDisabledWorkerConnection() throws SQLException {
        assertThat(queryStrings("""
            SELECT DISTINCT application_name FROM pg_stat_activity
            WHERE datname = current_database() AND application_name = '%s'
            """.formatted(DISABLED_WORKER_APPLICATION_NAME)))
            .as("쓰기 감사 대상으로 식별한 Worker의 실제 DB 연결")
            .containsExactly(DISABLED_WORKER_APPLICATION_NAME);
    }

    private static void assertNoScheduledTasks() {
        assertThat(statusOf(workerPort, SCHEDULED_TASKS_PATH)).isEqualTo(200);
        assertThat(bodyOf(workerPort, SCHEDULED_TASKS_PATH).replaceAll("\\s", ""))
            .contains("\"cron\":[]", "\"fixedDelay\":[]", "\"fixedRate\":[]", "\"custom\":[]");
    }

    private static List<String> queryStrings(String sql) throws SQLException {
        try (Connection connection = databaseConnection();
            Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery(sql)) {
            List<String> result = new ArrayList<>();
            while (rows.next()) {
                result.add(rows.getString(1));
            }
            return result;
        }
    }

    private static Connection databaseConnection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static List<String> projectLibraries(String jarProperty) throws IOException {
        try (JarFile jar = new JarFile(jarAt(jarProperty))) {
            return jar.stream()
                .map(entry -> entry.getName())
                .filter(name -> name.startsWith("BOOT-INF/lib/") && name.endsWith(".jar"))
                .flatMap(name -> PROJECT_MODULES.stream()
                    .filter(module -> name.startsWith("BOOT-INF/lib/" + module + "-")))
                .sorted()
                .toList();
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
