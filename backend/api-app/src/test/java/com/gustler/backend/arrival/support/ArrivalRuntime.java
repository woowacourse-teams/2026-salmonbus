package com.gustler.backend.arrival.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.testcontainers.containers.PostgreSQLContainer;
import com.gustler.backend.arrival.contract.ArrivalSnapshot;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 같은 임시 PostgreSQL을 사용하는 API·Worker 두 Boot JAR의 실행 환경. */
public final class ArrivalRuntime implements AutoCloseable {
    private static final String TOKEN = "local-arrival-runtime-token";
    private static final Duration BOOT_TIMEOUT = Duration.ofSeconds(60);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");
    public final FakeGbisArrivalServer source;
    private final Path logs = Path.of("build/reports/arrival-runtime-logs").toAbsolutePath();
    private final int apiPort = freePort();
    private final int workerPort = anotherPort(apiPort);
    private Process api;
    private Process worker;
    private int workerNumber;
    private final List<String> apiJvmOptions;

    public ArrivalRuntime() throws Exception {
        // 비동기 접수·대기열 회귀 시나리오는 대기 없이 검증한다.
        // 기본 500ms 동작은 API 설정을 생략한 생성자로 별도 검증한다.
        this(List.of(), Map.of("ARRIVALS_FIRST_RESULT_WAIT", "0ms"));
    }

    public ArrivalRuntime(List<String> apiJvmOptions, Map<String, String> apiSettings) throws Exception {
        this.apiJvmOptions = List.copyOf(apiJvmOptions);
        Files.createDirectories(logs);
        source = new FakeGbisArrivalServer();
        try {
            postgres.start();
            Map<String, String> settings = new HashMap<>(apiSettings);
            settings.put("ARRIVAL_WORKER_BASE_URL", workerUrl());
            api = start("api.boot.jar", apiPort, settings, logs.resolve("api.log"));
            seedCatalog();
        } catch (Exception failure) {
            close();
            throw failure;
        }
    }

    public record Reply(int httpStatus, JsonNode body, String cacheControl, double elapsedMillis) {
        public String value(String pointer) {
            return body.at(pointer).asString(null);
        }
    }

    /** 프로파일링 중에는 외부 수집 없이 신선한 DB 조회 경로만 유지한다. */
    public void seedFreshReceipt(int routeCount) throws SQLException {
        Instant received = Instant.now();
        var routes = new ArrayList<ArrivalSnapshot.RouteArrival>();
        for (int index = 0; index < routeCount; index++) {
            String route = index == 0 ? "204000057" : index == 1 ? "204000155" : Integer.toString(209000000 + index);
            routes.add(new ArrivalSnapshot.RouteArrival(route, "synthetic " + index, 1, 12, null, null, "RUN",
                List.of(new ArrivalSnapshot.Prediction("vehicle-" + index, 1, 3600, 60, 3, 0, null, null, 0, false))));
        }
        var snapshot = new ArrivalSnapshot(UUID.randomUUID(), FakeGbisArrivalServer.STATIONS.getFirst(), received,
            received.plusSeconds(3600), received.plusSeconds(3660), "synthetic profile", routes);
        try (Connection connection = connection(); var statement = connection.prepareStatement("""
            INSERT INTO arrival_snapshot(id,provider,operation,station_id,received_at,refresh_after_at,stale_at,
                normalization_version,payload_digest,payload)
            VALUES (?,'GBIS','ARRIVAL_LIST_V2',?,?,?,?,'gbis-arrival-v1',?,CAST(? AS jsonb))
            """)) {
            statement.setObject(1, snapshot.id());
            statement.setString(2, snapshot.stationId());
            statement.setTimestamp(3, java.sql.Timestamp.from(snapshot.receivedAt()));
            statement.setTimestamp(4, java.sql.Timestamp.from(snapshot.refreshAfterAt()));
            statement.setTimestamp(5, java.sql.Timestamp.from(snapshot.staleAt()));
            statement.setString(6, "b".repeat(64));
            statement.setString(7, JSON.writeValueAsString(snapshot));
            statement.executeUpdate();
        }
    }

    public URI arrivalsUri() {
        return URI.create("http://127.0.0.1:" + apiPort + "/api/v2/stations/" + FakeGbisArrivalServer.STATIONS.getFirst() + "/arrivals");
    }

    public void startWorker(boolean productionTimings, Map<String, String> extra) throws Exception {
        stopWorker();
        Map<String, String> settings = new HashMap<>();
        settings.put("GBIS_BASE_URL", source.baseUrl());
        settings.put("GBIS_SERVICE_KEY", "fake-arrival-key-a");
        if (!productionTimings) {
            settings.put("ARRIVALS_REFRESH_INTERVAL", "2s");
            settings.put("ARRIVALS_VALIDITY", "4s");
            settings.put("ARRIVALS_STARTUP_DELAY", "2s");
            settings.put("ARRIVALS_RETRY_BASE", "2s");
            settings.put("ARRIVALS_RETRY_MAXIMUM", "4s");
        }
        settings.putAll(extra);
        worker = start("worker.boot.jar", workerPort, settings, logs.resolve("worker-" + ++workerNumber + ".log"));
    }

    public void stopWorker() throws Exception {
        if (worker == null) return;
        source.release();
        destroy(worker);
        worker = null;
        await(source::idle, Duration.ofSeconds(5), "가짜 응답 종료");
    }

    public void reset() throws Exception {
        stopWorker();
        source.reset();
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM arrival_snapshot");
            statement.executeUpdate("DELETE FROM daily_call_quota WHERE api_service = 'BUS_ARRIVAL'");
        }
    }

    public Reply get(String station) throws Exception {
        return get(station, "");
    }

    public Reply get(String station, String query) throws Exception {
        String path = "/api/v2/stations/" + station + "/arrivals" + query;
        long started = System.nanoTime();
        var response = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + apiPort + path))
            .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), JSON.readTree(response.body()),
            response.headers().firstValue("Cache-Control").orElse(""), (System.nanoTime() - started) / 1e6);
    }

    public Reply awaitValue(String station, String pointer, String expected, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        Reply latest;
        do {
            latest = get(station);
            assertThat(latest.httpStatus()).as("%s 응답: %s", station, latest.body()).isEqualTo(200);
            if (expected.equals(latest.value(pointer))) return latest;
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        throw new AssertionError(pointer + " 값이 " + expected + " 상태로 바뀌지 않았다: " + latest.body());
    }

    public int snapshots() throws SQLException {
        return scalar("SELECT count(*) FROM arrival_snapshot");
    }

    public int reservations() throws SQLException {
        return scalar("SELECT COALESCE(sum(reserved_calls),0) FROM daily_call_quota "
            + "WHERE api_service='BUS_ARRIVAL' AND key_alias='__total__'");
    }

    public int usedKeys() throws SQLException {
        return scalar("SELECT count(*) FROM daily_call_quota WHERE api_service='BUS_ARRIVAL' "
            + "AND key_alias <> '__total__' AND reserved_calls > 0");
    }

    public int routeReservations() throws SQLException {
        return scalar("SELECT COALESCE(sum(reserved_calls),0) FROM daily_call_quota WHERE api_service='BUS_ROUTE'");
    }

    private int scalar(String sql) throws SQLException {
        try (Connection connection = connection(); var statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private void seedCatalog() throws SQLException {
        try (Connection connection = connection()) {
            String[][] routes = {{"204000057", "3330"}, {"204000155", "1650"}};
            for (String[] route : routes) {
                long routeId;
                try (var insert = connection.prepareStatement("""
                    INSERT INTO route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name)
                    VALUES (?,'GBIS',?,?, 'A','B') RETURNING id
                    """)) {
                    insert.setString(1, route[0]); insert.setString(2, route[0]); insert.setString(3, route[1]);
                    try (var rows = insert.executeQuery()) { rows.next(); routeId = rows.getLong(1); }
                }
                long version;
                try (var insert = connection.prepareStatement("""
                    INSERT INTO route_version(route_id,turn_sequence,content_digest,valid_from)
                    VALUES (?,12,?,clock_timestamp() - interval '1 day') RETURNING id
                    """)) {
                    insert.setLong(1, routeId); insert.setString(2, "a".repeat(64));
                    try (var rows = insert.executeQuery()) { rows.next(); version = rows.getLong(1); }
                }
                try (var insert = connection.prepareStatement("""
                    INSERT INTO route_stop(route_version_id,stop_order,stop_id,name,direction,boarding_allowed)
                    VALUES (?,?,?,'synthetic stop','UP',true)
                    """)) {
                    for (int index = 0; index < FakeGbisArrivalServer.STATIONS.size(); index++) {
                        insert.setLong(1, version); insert.setInt(2, index + 1);
                        insert.setString(3, FakeGbisArrivalServer.STATIONS.get(index)); insert.addBatch();
                    }
                    insert.executeBatch();
                }
            }
        }
    }

    private Process start(String jarProperty, int port, Map<String, String> extra, Path log) throws Exception {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(),
            "-Xmx256m", "-XX:ActiveProcessorCount=2"));
        if (jarProperty.equals("api.boot.jar")) command.addAll(apiJvmOptions);
        command.addAll(List.of("-jar", System.getProperty(jarProperty)));
        var builder = new ProcessBuilder(command);
        Map<String, String> environment = builder.environment();
        environment.clear();
        environment.putAll(Map.of("SERVER_PORT", Integer.toString(port), "SERVER_ADDRESS", "127.0.0.1",
            "SPRING_CONFIG_LOCATION", "classpath:/application.yml", "DB_URL", postgres.getJdbcUrl(),
            "DB_USERNAME", postgres.getUsername(), "DB_PASSWORD", postgres.getPassword(),
            "COLLECTION_ENABLED", "false", "FORECAST_ENABLED", "false", "ARRIVALS_ENABLED", "true",
            "ARRIVAL_INTERNAL_TOKEN", TOKEN));
        environment.put("LOGGING_LEVEL_ROOT", "WARN");
        environment.putAll(extra);
        builder.redirectErrorStream(true).redirectOutput(log.toFile());
        Process process = builder.start();
        try {
            await(() -> {
                if (!process.isAlive()) throw new AssertionError("실행 JAR 기동 실패: " + tail(log));
                try {
                    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health"))
                        .timeout(Duration.ofSeconds(1)).GET().build();
                    return HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
                } catch (Exception notReady) { return false; }
            }, BOOT_TIMEOUT, "Boot JAR 기동: " + log);
            return process;
        } catch (Throwable failure) {
            destroy(process);
            throw failure;
        }
    }

    public static void await(BooleanSupplier condition, Duration timeout, String description) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            if (condition.getAsBoolean()) return;
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        fail(description + " 시간 초과");
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
        catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }

    private static int anotherPort(int excluded) {
        int port;
        do { port = freePort(); } while (port == excluded);
        return port;
    }

    private String workerUrl() {
        return "http://127.0.0.1:" + workerPort;
    }

    private static String tail(Path path) {
        try {
            String content = Files.readString(path);
            return content.substring(Math.max(0, content.length() - 6000));
        } catch (IOException failure) { return "로그 읽기 실패"; }
    }

    private static void destroy(Process process) throws InterruptedException {
        if (process == null) return;
        process.destroy();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    @Override
    public void close() throws Exception {
        source.release();
        try { destroy(worker); }
        finally {
            try { destroy(api); }
            finally {
                source.close();
                if (postgres.isRunning()) postgres.stop();
            }
        }
    }
}
