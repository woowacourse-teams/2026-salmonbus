package com.gustler.backend.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class MaintenanceProcessTest {

    private static final Instant START = Instant.parse("2026-09-21T00:00:00Z");
    private static final Instant UNTIL = START.plusSeconds(60);
    private static final AtomicInteger DATABASE_SEQUENCE = new AtomicInteger();
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build();

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18")
        .withUsername("maintenance_process_user")
        .withPassword("maintenance-process-secret");

    @TempDir
    private Path directory;

    private int processSequence;

    @Test
    void 폐기한_이관_명령은_실제_JAR에서도_종료코드_1로_거절한다() throws Exception {
        Result result = execute("archive-import");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stdout()).isEmpty();
        Failure output = read(result.stderr(), Failure.class);
        assertThat(output).isEqualTo(new Failure("failed", "UNKNOWN_COMMAND"));
    }

    @Test
    void 실제_JAR은_인증키와_모델_없이_품질을_조회하고_DB를_변경하지_않는다() throws Exception {
        Database database = database(true);
        long version = route(database.jdbc());
        batch(database.jdbc(), version, 1, 1, 44);
        batch(database.jdbc(), version, 2, 2, 71);
        Map<String, String> before = state(database.jdbc());

        Result previewProcess = command(database, "quality-preview", version);
        assertThat(previewProcess.exitCode()).isZero();
        PreviewOutput preview = read(previewProcess.stdout(), PreviewOutput.class);
        assertThat(preview).isEqualTo(new PreviewOutput("succeeded",
            new Preview("LAST_32_BATCHES_SAMPLE", 2, 2, 1)));
        Result statusProcess = command(database, "quality-status", version);
        assertThat(statusProcess.exitCode()).isZero();
        StatusOutput status = read(statusProcess.stdout(), StatusOutput.class);
        assertThat(status.status()).isEqualTo("succeeded");
        assertThat(status.result()).isEmpty();
        assertThat(state(database.jdbc())).isEqualTo(before);
    }

    @Test
    void 실제_JAR의_정비는_한_페이지씩_커서를_이어가고_완료한_작업을_중복_반영하지_않는다() throws Exception {
        Database database = database(true);
        JdbcClient jdbc = database.jdbc();
        long version = route(jdbc);
        long first = batch(jdbc, version, 1, 1, 44);
        long bad = batch(jdbc, version, 2, 2, 71);
        long next = batch(jdbc, version, 3, 4, 44);

        ChunkOutput firstPage = rebuild(database, version);
        assertThat(firstPage.result()).isEqualTo(new Chunk(1, false, false, false));
        assertThat(discoveryCursor(jdbc, version)).isEqualTo(first);
        ChunkOutput secondPage = rebuild(database, version);
        assertThat(secondPage.result().processedBatches()).isEqualTo(1);
        assertThat(discoveryCursor(jdbc, version)).isEqualTo(bad);

        ChunkOutput result = secondPage;
        for (int attempt = 0; attempt < 8 && !result.result().completed(); attempt++) {
            long previous = discoveryCursor(jdbc, version);
            result = rebuild(database, version);
            assertThat(result.result().processedBatches()).isBetween(0, 1);
            assertThat(discoveryCursor(jdbc, version)).isBetween(previous, next);
        }
        assertThat(result.result().completed()).as("후속 출발 관측까지 있는 조사는 제한된 재호출로 끝나야 한다").isTrue();
        assertThat(discoveryCursor(jdbc, version)).isEqualTo(next);
        assertThat(jdbc.sql("SELECT remaining_seats FROM vehicle_observation ORDER BY id")
            .query(Integer.class).list()).containsExactly(44, 71, 44);
        assertThat(jdbc.sql("SELECT observation_batch_id FROM forecast_eligible_observation ORDER BY observation_batch_id")
            .query(Long.class).list()).containsExactly(next);
        assertThat(jdbc.sql("SELECT count(*) FROM observation_batch WHERE input_confirmed_at IS NOT NULL")
            .query(Long.class).single()).isPositive();

        StatusOutput status = read(command(database, "quality-status", version).stdout(), StatusOutput.class);
        assertThat(status.status()).isEqualTo("succeeded");
        assertThat(status.result()).extracting(StatusRow::vehicleId).containsExactly("", "bus-1");
        assertThat(status.result()).allMatch(StatusRow::completed);
        Map<String, String> completed = state(jdbc);
        ChunkOutput repeated = rebuild(database, version);
        assertThat(repeated.result()).isEqualTo(new Chunk(0, true, true, false));
        assertThat(state(jdbc)).isEqualTo(completed);
    }

    @Test
    void 스키마가_없는_DB에서는_자동_마이그레이션을_수행하지_않고_실패한다() throws Exception {
        Database database = database(false);

        Result result = command(database, "quality-status", 1);

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(read(result.stderr(), Failure.class))
            .isEqualTo(new Failure("failed", "DATABASE_TRANSACTION_FAILED"));
        assertThat(database.jdbc().sql("SELECT to_regclass('public.flyway_schema_history') IS NULL")
            .query(Boolean.class).single()).isTrue();
        assertThat(database.jdbc().sql("""
            SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p', 'v', 'm')
            """).query(Long.class).single()).isZero();
    }

    @Test
    void 정비_JAR에는_다른_실행_앱과_완료된_이관_자원이_없다() throws Exception {
        List<String> forbidden = new ArrayList<>();
        try (ZipFile jar = new ZipFile(jar().toFile())) {
            for (var entries = jar.entries(); entries.hasMoreElements();) {
                var entry = entries.nextElement();
                String name = entry.getName();
                if (forbiddenResource(name)) {
                    forbidden.add(name);
                }
                if (name.startsWith("BOOT-INF/lib/")) {
                    assertThat(name).doesNotContain("/api-app-", "/worker-app-", "/common-", "/flyway-");
                    try (var nested = new ZipInputStream(jar.getInputStream(entry))) {
                        for (var member = nested.getNextEntry(); member != null; member = nested.getNextEntry()) {
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
        String name = path.startsWith("BOOT-INF/classes/") ? path.substring("BOOT-INF/classes/".length()) : path;
        return name.startsWith("com/gustler/backend/api/") || name.startsWith("com/gustler/backend/worker/")
            || name.startsWith("com/gustler/backend/migration/") || name.startsWith("software/amazon/awssdk/") || name.startsWith("com/amazonaws/")
            || name.startsWith("db/migration/") || name.startsWith("db/historical-migration/");
    }

    private ChunkOutput rebuild(Database database, long version) throws Exception {
        Result process = command(database, "quality-rebuild", version);
        assertThat(process.exitCode()).isZero();
        ChunkOutput result = read(process.stdout(), ChunkOutput.class);
        assertThat(result.status()).isEqualTo("succeeded");
        return result;
    }

    private Result command(Database database, String command, long version) throws Exception {
        List<String> arguments = new ArrayList<>(List.of(command, "--config", database.configuration().toString(),
            "--route-version", Long.toString(version)));
        if (!command.equals("quality-status")) {
            arguments.addAll(List.of("--until", UNTIL.toString()));
        }
        if (command.equals("quality-rebuild")) {
            arguments.addAll(List.of("--batch-limit", "1"));
        }
        return execute(arguments.toArray(String[]::new));
    }

    private Result execute(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-jar", jar().toString()));
        command.addAll(List.of(arguments));
        Path stdout = directory.resolve("process-" + (++processSequence) + ".out");
        Path stderr = directory.resolve("process-" + processSequence + ".err");
        ProcessBuilder builder = new ProcessBuilder(command).redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
        builder.environment().keySet().removeIf(name -> name.startsWith("GBIS_") || name.startsWith("MODEL_")
            || name.startsWith("COLLECTION_") || name.startsWith("FORECAST_") || name.startsWith("SPRING_")
            || name.startsWith("DB_") || Set.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS").contains(name));
        Process process = builder.start();
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
                throw new AssertionError("정비 JAR 명령이 30초 안에 종료하지 않았다. 실행 로그 원문은 생략한다");
            }
            Result result = new Result(process.exitValue(), Files.readString(stdout).strip(), Files.readString(stderr).strip());
            boolean credentialsAbsent = !result.stdout().contains(POSTGRES.getPassword())
                && !result.stderr().contains(POSTGRES.getPassword());
            assertThat(credentialsAbsent).as("실행 로그와 실패 메시지는 DB 비밀번호를 포함하지 않아야 한다").isTrue();
            return result;
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private Database database(boolean migrate) throws Exception {
        String name = "maintenance_process_" + DATABASE_SEQUENCE.incrementAndGet();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + name;
        if (migrate) {
            Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()).load().migrate();
        }
        Path env = directory.resolve(name + ".env");
        Files.writeString(env, "DB_URL=" + url + "\nDB_USERNAME=" + POSTGRES.getUsername()
            + "\nDB_PASSWORD=" + POSTGRES.getPassword() + "\n");
        Files.setPosixFilePermissions(env, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        Path configuration = directory.resolve(name + ".properties");
        Properties properties = new Properties();
        properties.setProperty("target.kind", "LOCAL");
        properties.setProperty("database.env-file", env.toString());
        try (var stream = Files.newOutputStream(configuration)) {
            properties.store(stream, null);
        }
        Files.setPosixFilePermissions(configuration, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        return new Database(configuration,
            JdbcClient.create(new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())));
    }

    private static long route(JdbcClient jdbc) {
        long route = jdbc.sql("""
            INSERT INTO route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name)
            VALUES ('900000010','TEST','900000010','test','a','b') RETURNING id
            """).query(Long.class).single();
        long version = jdbc.sql("""
            INSERT INTO route_version(route_id,content_digest,valid_from,turn_sequence)
            VALUES (?,?,'2026-09-20T00:00:00Z',4) RETURNING id
            """).param(route).param("0".repeat(64)).query(Long.class).single();
        jdbc.sql("""
            INSERT INTO route_stop SELECT ?,n,'s'||n,'stop'||n,CASE WHEN n<=4 THEN 'UP' ELSE 'DOWN' END,true
            FROM generate_series(1,7) n
            """).param(version).update();
        jdbc.sql("INSERT INTO route_data_quality(route_id) VALUES (?)").param(route).update();
        jdbc.sql("INSERT INTO route_version_quality_policy(route_version_id) VALUES (?)").param(version).update();
        return version;
    }

    private static long batch(JdbcClient jdbc, long version, int step, int stop, int seats) {
        OffsetDateTime at = START.plusSeconds(step * 10L).atOffset(ZoneOffset.UTC);
        long batch = jdbc.sql("""
            INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,requested_at,
                response_received_at,outcome,normalization_version,collection_strategy_version)
            VALUES (?,?,1,?,?,?,'SUCCESS_ROWS','test','test') RETURNING id
            """).param(version).param(at).param("batch" + step).param(at).param(at).query(Long.class).single();
        jdbc.sql("""
            INSERT INTO vehicle_observation(observation_batch_id,route_version_id,source_row_number,vehicle_id,
                stop_order,stop_id,running_state,passed_stop_order,remaining_seats)
            VALUES (?,?,1,'bus-1',?,'s'||?,2,?,?)
            """).param(batch).param(version).param(stop).param(stop).param(stop).param(seats).update();
        return batch;
    }

    private static long discoveryCursor(JdbcClient jdbc, long version) {
        return jdbc.sql("SELECT last_batch_id FROM trip_quality_rebuild WHERE route_version_id=? AND vehicle_id=''")
            .param(version).query(Long.class).single();
    }

    private static Map<String, String> state(JdbcClient jdbc) {
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (String table : List.of("observation_batch", "vehicle_observation", "route_data_quality", "trip_quality_rebuild",
            "vehicle_one_way_trip", "observation_trip_assignment", "model_deployment", "forecast_publication",
            "seat_forecast", "forecast_evaluation", "demand_statistics_version", "stop_demand_statistics")) {
            snapshot.put(table, jdbc.sql("SELECT md5(coalesce(jsonb_agg(to_jsonb(r) ORDER BY to_jsonb(r)::text)::text,'[]')) FROM "
                + table + " r").query(String.class).single());
        }
        return snapshot;
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

    private record Database(Path configuration, JdbcClient jdbc) { }
    private record Result(int exitCode, String stdout, String stderr) {
        @Override public String toString() { return "Result[exitCode=" + exitCode + ", output omitted]"; }
    }
    private record Failure(String status, String code) { }
    private record PreviewOutput(String status, Preview result) { }
    private record Preview(String scope, @JsonProperty("sampled_batches") long sampledBatches,
        @JsonProperty("sampled_observations") long sampledObservations, @JsonProperty("sampled_above_range") long sampledAboveRange) { }
    private record ChunkOutput(String status, Chunk result) { }
    private record Chunk(int processedBatches, boolean discoveryCompleted, boolean completed, boolean waitingForObservations) { }
    private record StatusOutput(String status, List<StatusRow> result) { }
    private record StatusRow(@JsonProperty("vehicle_id") String vehicleId, String phase, boolean completed,
        @JsonProperty("evidence_observation_id") Long evidenceObservationId, @JsonProperty("last_batch_at") Instant lastBatchAt,
        @JsonProperty("last_batch_id") long lastBatchId, @JsonProperty("can_release") boolean canRelease,
        @JsonProperty("started_at") Instant startedAt, @JsonProperty("investigated_at") Instant investigatedAt) { }
}
