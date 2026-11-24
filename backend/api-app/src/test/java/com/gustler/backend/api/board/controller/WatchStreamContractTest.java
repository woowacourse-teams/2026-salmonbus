package com.gustler.backend.api.board.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gustler.backend.api.board.support.BoardDatabaseFixture;
import com.gustler.backend.api.board.support.BoardDatabaseFixture.RouteContext;
import com.gustler.backend.api.support.FixedDaytimeClockConfig;
import com.gustler.backend.support.PostgresTestContainer;
import com.gustler.backend.support.TestDatabase;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * MockMvc 는 톰캣의 비동기 수명 만료, 클라이언트 끊김, 응답 헤더가 나가는 순간을 재현하지 못하므로 실제 포트를 쓴다.
 *
 * <p>로그는 그 스트림 요청이 {@code http.server.requests} 에 기록된 뒤에 본다. 그 기록은 요청의 마지막 디스패치가 끝나야 남으므로,
 * 그때는 요청이 남길 로그가 모두 남아 있다.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
    "watch.stream.enabled=true",
    "watch.stream.max-connections=4",
    "watch.stream.heartbeat-interval=100ms",
    "watch.stream.timeout=5s",
    "watch.stream.detect-interval=100ms",
    WatchStreamContractTest.FAIL_FAST_IF_A_STREAM_HOLDS_A_DB_CONNECTION
})
@ActiveProfiles("api-docs-local")
@Import({PostgresTestContainer.class, FixedDaytimeClockConfig.class})
class WatchStreamContractTest {
    static final String FAIL_FAST_IF_A_STREAM_HOLDS_A_DB_CONNECTION = "spring.datasource.hikari.connection-timeout=2000";
    private static final String ROUTE_ID = "204000057";
    private static final String NEVER_SEEDED_ROUTE = "204000099";
    private static final String STREAM = streamOf(NEVER_SEEDED_ROUTE);
    private static final String STREAM_URI = "/api/v1/routes/{routeId}/watch/events";
    private static final String FIRST_FRAME = "retry:\\d+\\n: connected";
    private static final String PING = "event:ping\ndata:1";
    private static final Duration SETTLE = Duration.ofSeconds(5);
    private static final Duration FIRST_SIGNAL = Duration.ofSeconds(2);
    private static final List<String> WATCHED_LOGGERS = List.of("com.gustler.backend", "org.springframework.web", "org.apache");

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final List<WatchStreamClient> clients = new ArrayList<>();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    @LocalServerPort
    private int port;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Clock clock;

    @Autowired
    private TestDatabase database;

    @BeforeEach
    void captureLogs() {
        logs.start();
        rootLogger.addAppender(logs);
    }

    @AfterEach
    void closeStreams() throws IOException {
        rootLogger.detachAppender(logs);
        for (WatchStreamClient client : clients) {
            client.close();
        }
        await().atMost(SETTLE).until(() -> connections() == 0);
    }

    @Test
    void 연결하면_첫_쓰기와_주기적인_ping을_받다가_수명이_끝나면_서버가_경고_없이_닫는다() throws Exception {
        // given
        long finishedBefore = finishedStreams();

        // when
        WatchStreamClient client = open();
        String first = client.nextBlock();
        List<String> rest = new ArrayList<>();
        String block;
        while ((block = client.nextBlock()) != null) {
            rest.add(block);
        }

        // then
        assertThat(client.status()).isEqualTo(200);
        assertThat(client.headers("Content-Type")).containsExactly("text/event-stream");
        assertThat(client.headers("Cache-Control")).containsExactly("no-store, no-transform");
        assertThat(first).matches(FIRST_FRAME);
        assertThat(rest).hasSizeGreaterThanOrEqualTo(3).allMatch(PING::equals);
        assertThat(client.ended()).isTrue();
        await().atMost(SETTLE).until(() -> connections() == 0);
        assertNoServerWarnings(finishedBefore + 1);
    }

    @Test
    void 클라이언트가_끊어도_경고가_남지_않고_남은_연결은_ping을_계속_받는다() throws Exception {
        // given
        long finishedBefore = finishedStreams();
        WatchStreamClient gone = open();
        WatchStreamClient stays = open();
        assertThat(gone.nextBlock()).matches(FIRST_FRAME);
        assertThat(stays.nextBlock()).matches(FIRST_FRAME);

        // when
        gone.close();

        // then
        await().atMost(SETTLE).until(() -> connections() == 1);
        assertThat(stays.nextBlock()).isEqualTo(PING);
        assertThat(stays.nextBlock()).isEqualTo(PING);
        assertThat(connections()).isEqualTo(1);
        assertNoServerWarnings(finishedBefore + 1);
    }

    @Test
    void 연결_4개가_열려_있어도_watch는_200이고_사용_중인_DB_연결은_0개다() throws Exception {
        // given
        seedPublishedRoute(ROUTE_ID);
        List<Long> retries = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            WatchStreamClient client = open("Cookie: __Host-sb_session=" + staleSessionId());
            assertThat(client.status()).isEqualTo(200);
            String first = client.nextBlock();
            assertThat(first).matches(FIRST_FRAME);
            retries.add(Long.parseLong(first.substring("retry:".length(), first.indexOf('\n'))));
        }
        assertThat(connections()).isEqualTo(4);

        // when
        HttpResponse<String> watch = get("/api/v1/routes/" + ROUTE_ID + "/watch", "application/json");

        // then
        assertThat(watch.statusCode()).isEqualTo(200);
        await().atMost(Duration.ofSeconds(2)).until(() -> activeDatabaseConnections() == 0);
        assertThat(connections()).isEqualTo(4);
        assertThat(retries).allMatch(retry -> retry >= 3_000 && retry <= 10_000);
        assertThat(retries.stream().distinct().count()).isGreaterThan(1);
    }

    @Test
    void 상한을_넘은_연결은_204다() throws Exception {
        // given
        for (int index = 0; index < 4; index++) {
            assertThat(open().nextBlock()).matches(FIRST_FRAME);
        }

        // when
        HttpResponse<String> refused = get(STREAM, "text/event-stream");

        // then
        assertThat(refused.statusCode()).isEqualTo(204);
        assertThat(refused.body()).isEmpty();
        assertThat(refused.headers().firstValue("Content-Type")).isEmpty();
        assertThat(connections()).isEqualTo(4);
    }

    @Test
    void 노선_ID_형식이_틀리면_watch와_같은_400이다() throws Exception {
        // when
        HttpResponse<String> stream = get("/api/v1/routes/abc/watch/events", "text/event-stream");
        HttpResponse<String> watch = get("/api/v1/routes/abc/watch", "application/json");

        // then
        JsonNode streamBody = objectMapper.readTree(stream.body());
        JsonNode watchBody = objectMapper.readTree(watch.body());
        assertSoftly(softly -> {
            softly.assertThat(stream.statusCode()).isEqualTo(400);
            softly.assertThat(watch.statusCode()).isEqualTo(400);
            softly.assertThat(streamBody.path("code").asString()).isEqualTo("INVALID_ROUTE_ID");
            softly.assertThat(streamBody.path("code")).isEqualTo(watchBody.path("code"));
            softly.assertThat(streamBody.path("message")).isEqualTo(watchBody.path("message"));
            softly.assertThat(stream.headers().allValues("Content-Type"))
                .isEqualTo(watch.headers().allValues("Content-Type"));
            softly.assertThat(stream.headers().allValues("Cache-Control"))
                .isEqualTo(watch.headers().allValues("Cache-Control"));
        });
    }

    @Test
    void 노선의_첫_연결은_다음_확인_뒤에_observation을_하나_받고_그_값은_watch의_observedAt과_글자까지_같다() throws Exception {
        // given
        String routeId = "204000061";
        seedPublishedRoute(routeId);
        long finishedBefore = finishedStreams();

        // when
        long connecting = System.nanoTime();
        WatchStreamClient client = openRoute(routeId);
        String greeting = client.nextBlock();
        String signal = client.nextObservation();
        Duration untilSignal = Duration.ofNanos(System.nanoTime() - connecting);
        long readChecks = checks("read");
        List<String> rest = client.blocksThroughPingsAfter(() -> checks("read") >= readChecks + 3, 2);
        client.close();

        // then
        String observedAt = observedAtOfWatch(routeId);
        assertThat(greeting).matches(FIRST_FRAME);
        assertThat(untilSignal).isLessThan(FIRST_SIGNAL);
        assertThat(observedAt).endsWith(":00+09:00");
        assertThat(signal).isEqualTo("{\"observedAt\":\"" + observedAt + "\"}");
        assertThat(rest).allMatch(PING::equals);
        assertNoServerWarnings(finishedBefore + 1);
    }

    @Test
    void 새_관측이_생기면_그_노선의_연결마다_observation이_하나씩_오고_직후의_watch는_판정보드를_다시_만들지_않고_그_관측을_돌려준다()
        throws Exception {
        // given
        String routeId = "204000062";
        Seeded seeded = seedPublishedRoute(routeId);
        WatchStreamClient first = openRoute(routeId);
        assertThat(first.nextBlock()).matches(FIRST_FRAME);
        String known = first.nextObservation();
        WatchStreamClient second = openRoute(routeId);
        assertThat(second.nextBlock()).matches(FIRST_FRAME);
        assertThat(second.nextBlock()).isEqualTo("event:observation\ndata:" + known);
        WatchStreamClient elsewhere = openRoute(NEVER_SEEDED_ROUTE);
        assertThat(elsewhere.nextBlock()).matches(FIRST_FRAME);

        // when
        OffsetDateTime newer = seeded.observedAt().plusSeconds(40);
        long batch = seeded.fixture().insertBatch(seeded.route(), newer, "SUCCESS_EMPTY", 0);
        seeded.fixture().insertPublication(batch, seeded.model(), newer.plusSeconds(2));

        // then
        String announced = first.nextObservation();
        assertThat(second.nextObservation()).isEqualTo(announced);
        double missesBefore = cacheMisses();
        String watched = observedAtOfWatch(routeId);
        String watchedAgain = observedAtOfWatch(routeId);
        assertThat(announced).isEqualTo("{\"observedAt\":\"" + watched + "\"}");
        assertThat(watchedAgain).isEqualTo(watched);
        assertThat(OffsetDateTime.parse(watched)).isEqualTo(newer);
        assertThat(cacheMisses()).isEqualTo(missesBefore);
        long readChecks = checks("read");
        for (WatchStreamClient client : List.of(first, second, elsewhere)) {
            assertThat(client.blocksThroughPingsAfter(() -> checks("read") >= readChecks + 3, 2)).allMatch(PING::equals);
        }
    }

    @Test
    void 테이블이_잠겨도_확인은_제한_시간에_끝나고_잠금이_풀리면_새_관측을_알린다() throws Exception {
        // given
        String routeId = "204000064";
        Seeded seeded = seedPublishedRoute(routeId);
        WatchStreamClient client = openRoute(routeId);
        assertThat(client.nextBlock()).matches(FIRST_FRAME);
        assertThat(client.nextObservation()).isNotNull();

        // when
        try (Connection locker = database.connection()) {
            locker.setAutoCommit(false);
            try (Statement statement = locker.createStatement()) {
                statement.execute("LOCK TABLE forecast_run IN ACCESS EXCLUSIVE MODE");
            }
            long failedBefore = checks("failed");

            // then
            await().atMost(SETTLE).until(() -> waitsForForecastRun(locker));
            await().atMost(SETTLE).until(() -> checks("failed") > failedBefore);
            locker.rollback();
        }
        await().atMost(SETTLE).until(() -> activeDatabaseConnections() == 0);
        OffsetDateTime newer = seeded.observedAt().plusSeconds(30);
        long batch = seeded.fixture().insertBatch(seeded.route(), newer, "SUCCESS_EMPTY", 0);
        seeded.fixture().insertPublication(batch, seeded.model(), newer.plusSeconds(2));
        assertThat(OffsetDateTime.parse(observedAtIn(client.nextObservation()))).isEqualTo(newer);
    }

    @Test
    void 켜면_API_문서에_스트림_주소가_GET_하나로_나오고_200은_text_event_stream이다() throws Exception {
        // when
        HttpResponse<String> docs = get("/api/docs/openapi", "application/json");

        // then
        JsonNode document = objectMapper.readTree(docs.body());
        JsonNode stream = document.path("paths").path(STREAM_URI);
        JsonNode ok = stream.path("get").path("responses").path("200");
        assertSoftly(softly -> {
            softly.assertThat(docs.statusCode()).isEqualTo(200);
            softly.assertThat(document.path("paths").size()).isEqualTo(11);
            softly.assertThat(stream.propertyNames()).containsExactly("get");
            softly.assertThat(stream.path("get").path("operationId").asString()).isEqualTo("getRouteWatchEvents");
            softly.assertThat(stream.path("get").path("responses").propertyNames())
                .containsExactlyInAnyOrder("200", "204", "400");
            softly.assertThat(ok.path("content").propertyNames()).containsExactly("text/event-stream");
            softly.assertThat(ok.path("content").path("text/event-stream").path("schema").path("type").asString())
                .isEqualTo("string");
            softly.assertThat(ok.path("headers").has("Cache-Control")).isTrue();
            softly.assertThat(document.path("components").path("schemas").has("SseEmitter")).isFalse();
        });
    }

    private WatchStreamClient open(String... headers) throws IOException {
        WatchStreamClient client = WatchStreamClient.open(port, STREAM, headers);
        clients.add(client);
        return client;
    }

    private WatchStreamClient openRoute(String routeId) throws IOException {
        WatchStreamClient client = WatchStreamClient.open(port, streamOf(routeId));
        clients.add(client);
        return client;
    }

    private static String streamOf(String routeId) {
        return "/api/v1/routes/" + routeId + "/watch/events";
    }

    private String observedAtOfWatch(String routeId) throws IOException, InterruptedException {
        HttpResponse<String> watch = get("/api/v1/routes/" + routeId + "/watch", "application/json");
        assertThat(watch.statusCode()).isEqualTo(200);
        return objectMapper.readTree(watch.body()).path("observedAt").asString();
    }

    private String observedAtIn(String signal) {
        return objectMapper.readTree(signal).path("observedAt").asString();
    }

    private HttpResponse<String> get(String path, String accept) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Accept", accept)
            .GET()
            .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** 관측 시각은 초가 0 이다. {@code toString()} 과 JSON 의 글자가 달라지는 값이라 일부러 고른다. */
    private Seeded seedPublishedRoute(String routeId) {
        BoardDatabaseFixture fixture = new BoardDatabaseFixture(jdbcClient);
        OffsetDateTime now = OffsetDateTime.now(clock).withNano(0);
        RouteContext route = fixture.insertRoute(routeId, "3330", "기점", "종점", 2,
            "04:50", "23:30", "05:00", "23:30", now.minusDays(1));
        fixture.insertStop(route, 1, "STOP-1", "기점", "UP", true);
        fixture.insertStop(route, 2, "STOP-2", "회차점", "UP", false);
        fixture.insertStop(route, 3, "STOP-3", "종점", "DOWN", true);
        long model = jdbcClient.sql("SELECT id FROM model_deployment WHERE state = 'ACTIVE'")
            .query(Long.class)
            .optional()
            .orElseGet(() -> fixture.insertModel("model-active", "ACTIVE", now.minusDays(1)));
        OffsetDateTime observedAt = now.minusMinutes(1);
        long batch = fixture.insertBatch(route, observedAt, "SUCCESS_EMPTY", 0);
        fixture.insertPublication(batch, model, observedAt.plusSeconds(2));
        return new Seeded(fixture, route, model, observedAt);
    }

    private record Seeded(BoardDatabaseFixture fixture, RouteContext route, long model, OffsetDateTime observedAt) {
    }

    private long checks(String result) {
        Timer timer = meters.find("salmonbus.watch.stream.check").tag("result", result).timer();
        return timer == null ? 0 : timer.count();
    }

    private double cacheMisses() {
        Counter misses = meters.find("board.read.cache").tag("result", "miss").counter();
        return misses == null ? 0 : misses.count();
    }

    private static boolean waitsForForecastRun(Connection locker) throws SQLException {
        try (Statement statement = locker.createStatement();
            ResultSet waiters = statement.executeQuery(
                "SELECT count(*) FROM pg_locks WHERE NOT granted AND relation = 'forecast_run'::regclass")) {
            waiters.next();
            return waiters.getInt(1) > 0;
        }
    }

    /** Spring Session 은 쿠키 값이 base64 로 풀릴 때만 그 ID 로 세션을 찾는다. 풀리지 않는 값이면 DB 를 읽지 않는다. */
    private static String staleSessionId() {
        return Base64.getEncoder().encodeToString(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
    }

    private double connections() {
        return meters.get("salmonbus.watch.stream.connections").gauge().value();
    }

    private int activeDatabaseConnections() throws Exception {
        return dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean().getActiveConnections();
    }

    private long finishedStreams() {
        return meters.find("http.server.requests").tag("uri", STREAM_URI).timers().stream()
            .mapToLong(Timer::count)
            .sum();
    }

    private void assertNoServerWarnings(long finishedStreams) {
        await().atMost(SETTLE).until(() -> finishedStreams() >= finishedStreams);
        List<String> warnings = Stream.of(logs.list.toArray(ILoggingEvent[]::new))
            .filter(event -> event.getFormattedMessage().contains("event=api_failure")
                || event.getLevel().isGreaterOrEqual(Level.WARN) && isWatched(event.getLoggerName()))
            .map(event -> event.getLevel() + " " + event.getLoggerName() + " " + event.getFormattedMessage())
            .toList();
        assertThat(warnings).isEmpty();
    }

    private static boolean isWatched(String loggerName) {
        return WATCHED_LOGGERS.stream().anyMatch(loggerName::startsWith);
    }
}
