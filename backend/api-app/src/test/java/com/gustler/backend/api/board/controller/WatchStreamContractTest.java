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
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
    WatchStreamContractTest.FAIL_FAST_IF_A_STREAM_HOLDS_A_DB_CONNECTION
})
@Import({PostgresTestContainer.class, FixedDaytimeClockConfig.class})
class WatchStreamContractTest {
    static final String FAIL_FAST_IF_A_STREAM_HOLDS_A_DB_CONNECTION = "spring.datasource.hikari.connection-timeout=2000";
    private static final String ROUTE_ID = "204000057";
    private static final String STREAM = "/api/v1/routes/" + ROUTE_ID + "/watch/events";
    private static final String STREAM_URI = "/api/v1/routes/{routeId}/watch/events";
    private static final String FIRST_FRAME = "retry:\\d+\\n: connected";
    private static final String PING = "event:ping\ndata:1";
    private static final Duration SETTLE = Duration.ofSeconds(5);
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
        seedPublishedRoute();
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

    private WatchStreamClient open(String... headers) throws IOException {
        WatchStreamClient client = WatchStreamClient.open(port, STREAM, headers);
        clients.add(client);
        return client;
    }

    private HttpResponse<String> get(String path, String accept) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Accept", accept)
            .GET()
            .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void seedPublishedRoute() {
        BoardDatabaseFixture fixture = new BoardDatabaseFixture(jdbcClient);
        OffsetDateTime now = OffsetDateTime.now(clock).withNano(0);
        RouteContext route = fixture.insertRoute(ROUTE_ID, "3330", "기점", "종점", 2,
            "04:50", "23:30", "05:00", "23:30", now.minusDays(1));
        fixture.insertStop(route, 1, "STOP-1", "기점", "UP", true);
        fixture.insertStop(route, 2, "STOP-2", "회차점", "UP", false);
        fixture.insertStop(route, 3, "STOP-3", "종점", "DOWN", true);
        long model = fixture.insertModel("model-active", "ACTIVE", now.minusDays(1));
        long batch = fixture.insertBatch(route, now.minusMinutes(1), "SUCCESS_EMPTY", 0);
        fixture.insertPublication(batch, model, now.minusSeconds(58));
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
