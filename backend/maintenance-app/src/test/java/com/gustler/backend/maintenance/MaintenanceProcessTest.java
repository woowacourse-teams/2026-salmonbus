package com.gustler.backend.maintenance;

import com.gustler.backend.support.LoopbackRelay;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class MaintenanceProcessTest {
    private static final Instant START = Instant.parse("2026-09-21T00:00:00Z");
    private static final Instant UNTIL = START.plusSeconds(60);
    private static final AtomicInteger DATABASE_SEQUENCE = new AtomicInteger();
    private static LoopbackRelay loopback;
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build();

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
        .withUsername("maintenance_process_user")
        .withPassword("maintenance-process-secret");

    @TempDir
    private Path directory;

    private int processSequence;

    @Test
    void 폐기한_이관_명령은_실제_JAR에서도_종료코드_1로_거절한다() throws Exception {
        // when
        Result result = execute("archive-import");

        // then
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stdout()).isEmpty();
        Failure output = read(result.stderr(), Failure.class);
        assertThat(output).isEqualTo(new Failure("failed", "UNKNOWN_COMMAND"));
    }

    @Test
    void 정비_JAR에는_다른_실행_앱과_완료된_이관_자원이_없다() throws Exception {
        // when
        List<String> forbidden = new ArrayList<>();
        try (ZipFile jar = new ZipFile(jar().toFile())) {
            for (Enumeration<? extends ZipEntry> entries = jar.entries(); entries.hasMoreElements();) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (forbiddenResource(name)) {
                    forbidden.add(name);
                }
                if (name.startsWith("BOOT-INF/lib/")) {

                    // then
                    assertThat(name).doesNotContain("/api-app-", "/worker-app-", "/common-", "/flyway-");
                    try (ZipInputStream nested = new ZipInputStream(jar.getInputStream(entry))) {
                        for (ZipEntry member = nested.getNextEntry(); member != null; member = nested.getNextEntry()) {
                            if (forbiddenResource(member.getName())) {
                                forbidden.add(name + "!/" + member.getName());
                            }
                        }
                    }
                }
            }
        }
        assertThat(forbidden).isEmpty();
    }

    private static boolean forbiddenResource(String path) {
        String name = path;
        if (path.startsWith("BOOT-INF/classes/")) {
            name = path.substring("BOOT-INF/classes/".length());
        }
        return name.startsWith("com/gustler/backend/api/") || name.startsWith("com/gustler/backend/worker/")
            || name.startsWith("com/gustler/backend/migration/") || name.startsWith("software/amazon/awssdk/")
            || name.startsWith("com/amazonaws/")
            || name.startsWith("db/migration/") || name.startsWith("db/historical-migration/");
    }

    @Test
    void 정비JAR에서_형상상태_내보내기_등록_해제를_끝까지_실행한다() throws Exception {
        Database database = database(true);
        com.gustler.backend.routegeometry.api.RouteDisplayDbSamples.seed(
                database.jdbc(), "204000057");
        String config = database.configuration().toString();
        Result exported =
                execute("route-display-export", "--config", config, "--route-id", "204000057");
        assertThat(exported.exitCode()).isZero();
        assertThat(exported.stdout())
                .contains(
                        com.gustler.backend.routegeometry.api.RouteDisplaySamples.input().digest());
        Path artifact = directory.resolve("reviewed-display.json");
        Files.write(
                artifact,
                JSON.writeValueAsBytes(
                        com.gustler.backend.routegeometry.api.RouteDisplaySamples.manual(
                                com.gustler.backend.routegeometry.api.RouteDisplaySamples.input(),
                                0.0001)));
        Result imported =
                execute(
                        "route-display-import",
                        "--config",
                        config,
                        "--artifact",
                        artifact.toString());
        assertThat(imported.exitCode()).isZero();
        assertThat(imported.stdout()).contains("MANUAL_REVIEWED");
        Result status =
                execute("route-display-status", "--config", config, "--route-id", "204000057");
        assertThat(status.exitCode()).isZero();
        assertThat(status.stdout()).contains("MANUAL_REVIEWED");
        Result disabled =
                execute(
                        "route-display-disable",
                        "--config",
                        config,
                        "--route-id",
                        "204000057",
                        "--input-digest",
                        com.gustler.backend.routegeometry.api.RouteDisplaySamples.input().digest());
        assertThat(disabled.exitCode()).isZero();
        assertThat(disabled.stdout()).contains("AUTOMATIC_FALLBACK");

        var failedJob = RouteDisplayRetryFixtures.failed(database.jdbc());
        Result retried = execute("route-display-retry", "--config", config, "--route-id", "204000057",
                "--generation-key", failedJob.key());
        assertThat(retried.exitCode()).isZero();
        assertThat(retried.stdout()).contains("\"attemptLimit\":10", "\"attempts\":5", "\"status\":\"PENDING\"");
        Result duplicateRetry = execute("route-display-retry", "--config", config, "--route-id", "204000057",
                "--generation-key", failedJob.key());
        assertThat(duplicateRetry.exitCode()).isEqualTo(1);
        assertThat(duplicateRetry.stderr()).contains("ROUTE_DISPLAY_JOB_NOT_FAILED");
        assertThat(database.jdbc().sql("SELECT attempt_limit FROM route_display_generation_job")
                .query(Integer.class).single()).isEqualTo(10);
    }

    private Result execute(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java")
                .toString(),
            "-Xmx256m", "-jar", jar().toString()));
        command.addAll(List.of(arguments));
        Path stdout = directory.resolve("process-" + (++processSequence) + ".out");
        Path stderr = directory.resolve("process-" + processSequence + ".err");
        ProcessBuilder builder = new ProcessBuilder(command).redirectOutput(stdout.toFile())
            .redirectError(stderr.toFile());
        builder.environment().keySet().removeIf(name -> name.startsWith("GBIS_") || name.startsWith("MODEL_")
                || name.startsWith("COLLECTION_") || name.startsWith("FORECAST_") || name.startsWith("SPRING_")
                || name.startsWith("DB_") || Set.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")
                .contains(name));
        Process process = builder.start();
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
                throw new AssertionError("정비 JAR 명령이 30초 안에 종료하지 않았다. 실행 로그 원문은 생략한다");
            }
            Result result = new Result(process.exitValue(), Files.readString(stdout)
                .strip(), Files.readString(stderr)
                .strip());
            final boolean credentialsAbsent = !result.stdout().contains(POSTGRES.getPassword())
                && !result.stderr().contains(POSTGRES.getPassword());
            assertThat(credentialsAbsent).as("실행 로그와 실패 메시지는 DB 비밀번호를 포함하지 않아야 한다").isTrue();
            return result;
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static synchronized int loopbackPort() throws IOException {
        if (loopback == null) {
            loopback = new LoopbackRelay(POSTGRES.getHost(), POSTGRES.getMappedPort(5432));
        }
        return loopback.port();
    }

    @AfterAll
    static void closeLoopback() throws IOException {
        if (loopback != null) {
            loopback.close();
        }
    }

    private Database database(final boolean migrate) throws Exception {
        String name = "maintenance_process_" + DATABASE_SEQUENCE.incrementAndGet();
        try (Connection connection =
            DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
            Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + name;
        if (migrate) {
            Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()).load().migrate();
        }
        Path env = directory.resolve(name + ".env");
        String processUrl = "jdbc:postgresql://127.0.0.1:" + loopbackPort() + "/" + name;
        Files.writeString(env, "DB_URL=" + processUrl + "\nDB_USERNAME=" + POSTGRES.getUsername()
                + "\nDB_PASSWORD=" + POSTGRES.getPassword() + "\n");
        Files.setPosixFilePermissions(env, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        Path configuration = directory.resolve(name + ".properties");
        Properties properties = new Properties();
        properties.setProperty("target.kind", "LOCAL");
        properties.setProperty("database.env-file", env.toString());
        try (OutputStream stream = Files.newOutputStream(configuration)) {
            properties.store(stream, null);
        }
        Files.setPosixFilePermissions(configuration,
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        return new Database(configuration,
            JdbcClient.create(new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())));
    }

    private static Path jar() {
        String file = System.getProperty("maintenance.boot.jar");
        assertThat(file).as("Gradle이 실제 정비 Boot JAR 경로를 전달해야 한다").isNotBlank();
        Path path = Path.of(file);
        assertThat(Files.isRegularFile(path)).as("정비 Boot JAR 파일이 있어야 한다").isTrue();
        return path;
    }

    private static <T> T read(String json, Class<T> type) {
        try {
            return JSON.readValue(json, type);
        } catch (RuntimeException error) {
            throw new AssertionError("정비 명령의 JSON 출력이 계약과 다르다. 출력 원문은 생략한다");
        }
    }

    private record Database(Path configuration, JdbcClient jdbc) {
    }
    private record Result(int exitCode, String stdout, String stderr) {
        @Override
        public String toString() {
            return "Result[exitCode=" + exitCode + ", output omitted]";
        }
    }
    private record Failure(String status, String code) {
    }
    private record PreviewOutput(String status, Preview result) {
    }
    private record Preview(String scope, @JsonProperty("sampled_batches")
        long sampledBatches,
        @JsonProperty("sampled_observations")
        long sampledObservations, @JsonProperty("sampled_above_range")
        long sampledAboveRange) {
    }
    private record ChunkOutput(String status, Chunk result) {
    }
    private record Chunk(int processedBatches, boolean discoveryCompleted, boolean completed) {
    }
    private record StatusOutput(String status, List<StatusRow> result) {
    }
    private record StatusRow(@JsonProperty("vehicle_id")
        String vehicleId, String phase, boolean completed,
        @JsonProperty("evidence_observation_id")
        Long evidenceObservationId, @JsonProperty("last_batch_at")
        Instant lastBatchAt,
        @JsonProperty("last_batch_id")
        long lastBatchId, @JsonProperty("can_release")
        boolean canRelease,
        @JsonProperty("started_at")
        Instant startedAt, @JsonProperty("investigated_at")
        Instant investigatedAt) {
    }
}
