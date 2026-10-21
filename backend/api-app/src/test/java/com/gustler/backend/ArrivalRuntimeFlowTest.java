package com.gustler.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.arrival.support.ArrivalRuntime;
import com.gustler.backend.arrival.support.ArrivalRuntime.Reply;
import com.gustler.backend.arrival.support.FakeGbisArrivalServer;
import com.gustler.backend.arrival.support.FakeGbisArrivalServer.Plan;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.AutoCloseableSoftAssertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.extension.TestWatcher;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import tools.jackson.databind.ObjectMapper;

/**
 * 실제 두 실행 JAR·임시 DB·가짜 GBIS를 연결한다. 실제 공공 API를 호출하지 않는다.
 *
 * <p>실행: {@code ./gradlew :api-app:test --tests com.gustler.backend.ArrivalRuntimeFlowTest}
 * 기본 시간 설정의 시나리오는 실제 40초 갱신을 기다리고, 나머지는 2초/4초로 단축한다.
 */
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(180)
class ArrivalRuntimeFlowTest {
    private static final String STATION = FakeGbisArrivalServer.STATIONS.getFirst();
    private static final String OTHER = FakeGbisArrivalServer.STATIONS.get(1);
    private static final Duration QUICK_TIMEOUT = Duration.ofSeconds(8);
    private static final Path REPORT = Path.of("build/reports/arrival-runtime-flow.json");
    private static final Map<String, Object> EVIDENCE = new LinkedHashMap<>();
    private static final Map<String, String> OUTCOMES = new LinkedHashMap<>();
    private static ArrivalRuntime runtime;

    @RegisterExtension
    final TestWatcher outcomes = new TestWatcher() {
        @Override
        public void testSuccessful(ExtensionContext context) {
            OUTCOMES.put(context.getRequiredTestMethod().getName(), "PASSED");
        }

        @Override
        public void testFailed(ExtensionContext context, Throwable cause) {
            OUTCOMES.put(context.getRequiredTestMethod().getName(), "FAILED: " + cause.getClass().getSimpleName());
        }
    };

    @BeforeAll
    static void startApiAndDatabase() throws Exception {
        Files.createDirectories(REPORT.getParent());
        Files.deleteIfExists(REPORT);
        runtime = new ArrivalRuntime();
    }

    @BeforeEach
    void reset() throws Exception {
        runtime.reset();
    }

    @AfterEach
    void stopWorker() throws Exception {
        runtime.stopWorker();
        assertThat(runtime.source.unexpected()).isEmpty();
    }

    @AfterAll
    static void stopAndReport() throws Exception {
        try {
            Map<String, Object> report = Map.of("generatedAt", Instant.now()
                .toString(), "scope", "local HTTP / separate Boot JARs / PostgreSQL 18 / synthetic GBIS",
                "jvm", "Java 21, -Xmx256m, ActiveProcessorCount=2 per child JVM", "realPublicApiCalls", 0,
                "outcomes", OUTCOMES, "evidence", EVIDENCE);
            Files.writeString(REPORT, new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report));
        } finally {
            if (runtime != null) {
                runtime.close();
            }
        }
    }

    @Test
    void 기본_40초_갱신_60초_유효기간에서_동시요청과_캐시공유_갱신을_확인한다() throws Exception {
        // given
        Plan held = Plan.held();
        runtime.source.plan(STATION, held);
        runtime.startWorker(true, Map.of());

        // when
        Reply starting = runtime.get(STATION);

        // then
        assertThat(starting.httpStatus()).isEqualTo(200);
        assertThat(starting.value("/status")).isEqualTo("UNAVAILABLE");
        assertThat(starting.value("/refresh/reason")).isEqualTo("STARTING");
        assertThat(starting.cacheControl()).isEqualTo("no-store");
        assertThat(runtime.source.calls()).isZero();
        runtime.awaitValue(STATION, "/refresh/reason", "COLLECTING", Duration.ofSeconds(50));
        ArrivalRuntime.await(() -> runtime.source.calls() == 1, QUICK_TIMEOUT, "가짜 GBIS 요청 접수");

        List<Reply> cold = burst(STATION, 100);
        assertThat(cold).allSatisfy(reply -> {
            assertThat(reply.httpStatus()).isEqualTo(200);
            assertThat(reply.value("/status")).isEqualTo("UNAVAILABLE");
        });
        assertThat(runtime.source.calls()).isEqualTo(1);
        held.release();
        Reply ready = runtime.awaitValue(STATION, "/status", "READY", QUICK_TIMEOUT);
        assertThat(ready.cacheControl()).isEqualTo("max-age=5, public");
        assertThat(ready.body().path("routes")).hasSize(2);
        assertThat(ready.body().at("/routes/0/arrivals/0/currentSeat/remaining").intValue()).isZero();
        assertThat(ready.body().at("/snapshot/sourceObservedAt").isNull()).isTrue();
        assertThat(Duration.between(instant(ready, "receivedAt"), instant(ready, "refreshAfterAt"))).isEqualTo(
            Duration.ofSeconds(40));
        assertThat(Duration.between(instant(ready, "receivedAt"), instant(ready, "staleAt"))).isEqualTo(
            Duration.ofSeconds(60));

        String id = ready.value("/snapshot/snapshotId");
        String version = ready.value("/routes/0/targetVisit/referenceVersionId");
        Reply selected = runtime.get(STATION, "?routeId=204000057&referenceVersionId=" + version + "&stopSequence=1");
        assertThat(selected.body().path("routes")).hasSize(1);
        assertThat(selected.value("/snapshot/snapshotId")).isEqualTo(id);
        assertThat(runtime.get(STATION, "?routeId=204000155").value("/snapshot/snapshotId")).isEqualTo(id);
        assertThat(runtime.get(STATION, "?routeId=204000057&referenceVersionId=999999&stopSequence=1")
                .httpStatus()).isEqualTo(409);

        List<Reply> warm = burst(STATION, 100);
        assertThat(warm).allSatisfy(reply -> {
            assertThat(reply.httpStatus()).isEqualTo(200);
            assertThat(reply.value("/status")).isEqualTo("READY");
            assertThat(reply.value("/snapshot/snapshotId")).isEqualTo(id);
        });
        assertThat(runtime.source.calls()).isEqualTo(1);
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(runtime.reservations()).isEqualTo(1);
            softly.assertThat(runtime.snapshots()).isEqualTo(1);
        }
        EVIDENCE.put("cold100", timings(cold));
        EVIDENCE.put("warm100", timings(warm));

        pauseUntil(instant(ready, "refreshAfterAt").plusMillis(20));
        runtime.get(STATION);
        ArrivalRuntime.await(() -> snapshots() == 2, QUICK_TIMEOUT, "40초 뒤 새 수신 INSERT");
        Reply refreshed = runtime.get(STATION);
        assertThat(refreshed.value("/status")).isEqualTo("READY");
        assertThat(refreshed.value("/snapshot/snapshotId")).isNotEqualTo(id);
        assertThat(runtime.source.calls()).isEqualTo(2);
        assertThat(runtime.reservations()).isEqualTo(2);
        EVIDENCE.put("productionTimings", Map.of("refreshSeconds", 40, "validitySeconds", 60,
            "httpCacheSeconds", 5, "sourceCalls", 2, "receipts", 2, "reservations", 2));
    }

    @Test
    void 갱신실패는_기존_수신의_유효기간을_연장하지_않는다() throws Exception {
        // when
        runtime.startWorker(false, Map.of());
        Reply ready = runtime.awaitValue(STATION, "/status", "READY", QUICK_TIMEOUT);
        runtime.source.plan(STATION, Plan.error(503, "20"));
        pauseUntil(instant(ready, "refreshAfterAt").plusMillis(20));
        Reply failed = runtime.awaitValue(STATION, "/refresh/reason", "UPSTREAM_ERROR", QUICK_TIMEOUT);

        // then
        assertThat(failed.value("/status")).isEqualTo("READY");
        assertThat(failed.value("/snapshot/snapshotId")).isEqualTo(ready.value("/snapshot/snapshotId"));
        assertThat(failed.value("/snapshot/staleAt")).isEqualTo(ready.value("/snapshot/staleAt"));
        pauseUntil(instant(ready, "staleAt").plusMillis(20));
        Reply stale = runtime.get(STATION);
        assertThat(stale.value("/status")).isEqualTo("UNAVAILABLE");
        assertThat(stale.value("/reason")).isEqualTo("STALE");
        assertThat(stale.cacheControl()).isEqualTo("no-store");
        assertThat(stale.body().path("routes")).isEmpty();
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(runtime.snapshots()).isEqualTo(1);
            softly.assertThat(runtime.reservations()).isEqualTo(2);
        }
        assertThat(runtime.source.calls()).isEqualTo(2);
    }

    @Test
    void HTTP429의_RetryAfter는_다른_정류장의_재호출도_막는다() throws Exception {
        // when
        runtime.source.plan(STATION, Plan.error(429, "20"));
        runtime.startWorker(false, Map.of());
        Reply limited = runtime.awaitValue(STATION, "/refresh/reason", "RATE_LIMITED", QUICK_TIMEOUT);

        // then
        assertThat(Instant.parse(limited.value("/refresh/suggestedRefreshAt"))).isAfter(Instant.now().plusSeconds(15));
        Reply other = runtime.get(OTHER);
        assertThat(other.value("/refresh/reason")).isEqualTo("RATE_LIMITED");
        assertThat(runtime.source.calls()).isEqualTo(1);
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(runtime.reservations()).isEqualTo(1);
            softly.assertThat(runtime.snapshots()).isZero();
        }
    }

    @Test
    void 느린_상류에서_대기열은_8개까지만_받고_시간지난_작업은_예산을_쓰지_않는다() throws Exception {
        // when
        runtime.source.plan(STATION, Plan.slow(Duration.ofSeconds(3)));
        runtime.startWorker(false, Map.of());
        runtime.awaitValue(STATION, "/refresh/reason", "COLLECTING", QUICK_TIMEOUT);
        ArrivalRuntime.await(() -> runtime.source.calls() == 1, QUICK_TIMEOUT, "느린 가짜 응답 시작");
        List<Reply> queued = new ArrayList<>();
        for (String station : FakeGbisArrivalServer.STATIONS.subList(1, 10)) {
            queued.add(runtime.get(station));
        }

        // then
        assertThat(queued.stream()
                .filter(reply -> "BUSY".equals(reply.value("/refresh/reason")))
                .count()).isEqualTo(1);
        ArrivalRuntime.await(() -> snapshots() == 1, QUICK_TIMEOUT, "느린 응답 저장");
        Reply expired = runtime.get(OTHER);
        assertThat(expired.value("/refresh/reason")).isEqualTo("QUEUE_EXPIRED");
        assertThat(runtime.source.calls()).isEqualTo(1);
        assertThat(runtime.source.maximumActive()).isEqualTo(1);
        assertThat(runtime.reservations()).isEqualTo(1);
        EVIDENCE.put("slowSourceQueue", Map.of("stations", 10, "queued", 8, "busy", 1,
            "sourceCalls", 1, "maximumSourceConcurrency", 1, "reservations", 1));
    }

    @Test
    void 상류_5초_타임아웃은_수신기록을_만들지_않고_즉시_재호출하지_않는다() throws Exception {
        // when
        Plan held = Plan.held();
        runtime.source.plan(STATION, held);
        runtime.startWorker(false, Map.of());
        Reply failed = runtime.awaitValue(STATION, "/refresh/reason", "TRANSPORT_ERROR", Duration.ofSeconds(10));

        // then
        assertThat(failed.value("/status")).isEqualTo("UNAVAILABLE");
        assertThat(runtime.get(STATION).value("/refresh/reason")).isEqualTo("TRANSPORT_ERROR");
        assertThat(runtime.source.calls()).isEqualTo(1);
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(runtime.reservations()).isEqualTo(1);
            softly.assertThat(runtime.snapshots()).isZero();
        }
        held.release();
    }

    @Test
    void Worker가_중단돼도_유효값을_제공하고_만료후에는_숨긴다() throws Exception {
        // when
        runtime.startWorker(false, Map.of());
        Reply ready = runtime.awaitValue(STATION, "/status", "READY", QUICK_TIMEOUT);
        runtime.stopWorker();
        pauseUntil(instant(ready, "refreshAfterAt").plusMillis(20));
        Reply disconnected = runtime.get(STATION);

        // then
        assertThat(disconnected.value("/status")).isEqualTo("READY");
        assertThat(disconnected.value("/refresh/reason")).isEqualTo("WORKER_UNAVAILABLE");
        pauseUntil(instant(ready, "staleAt").plusMillis(20));
        assertThat(runtime.get(STATION).value("/reason")).isEqualTo("STALE");
        assertThat(runtime.snapshots()).isEqualTo(1);
        assertThat(runtime.source.calls()).isEqualTo(1);
    }

    @Test
    void 키별_한도와_전체_한도는_Worker_재기동_후에도_유지된다() throws Exception {
        // when
        Map<String, String> budget = Map.of("ARRIVALS_KEY_DAILY_LIMIT", "1",
            "GBIS_SERVICE_KEY_B", "fake-arrival-key-b");
        runtime.startWorker(false, budget);
        runtime.awaitValue(STATION, "/status", "READY", QUICK_TIMEOUT);
        runtime.awaitValue(OTHER, "/status", "READY", QUICK_TIMEOUT);
        String third = FakeGbisArrivalServer.STATIONS.get(2);
        runtime.awaitValue(third, "/refresh/reason", "DAILY_QUOTA_EXHAUSTED", QUICK_TIMEOUT);

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(runtime.reservations()).isEqualTo(2);
            softly.assertThat(runtime.usedKeys()).isEqualTo(2);
        }
        assertThat(runtime.source.calls()).isEqualTo(2);
        runtime.startWorker(false, budget);
        runtime.awaitValue(third, "/refresh/reason", "DAILY_QUOTA_EXHAUSTED", QUICK_TIMEOUT);
        assertThat(runtime.reservations()).isEqualTo(2);
        assertThat(runtime.source.calls()).isEqualTo(2);
        EVIDENCE.put("quotaAndRestart", Map.of("dailyLimit", 2, "keyLimit", 1, "usedKeys", 2,
            "sourceCallsAfterRestart", 2, "reservationsAfterRestart", 2));
    }

    private static List<Reply> burst(String station, final int count) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Reply>> futures = new ArrayList<>();
        try (ExecutorService clients = Executors.newFixedThreadPool(20)) {
            for (int index = 0; index < count; index++) {
                futures.add(clients.submit(() -> {
                    start.await();
                    return runtime.get(station);
                }));
            }
            start.countDown();
            List<Reply> replies = new ArrayList<>();
            for (Future<Reply> future : futures) {
                replies.add(future.get(10, TimeUnit.SECONDS));
            }
            return replies;
        }
    }

    private static Map<String, Object> timings(List<Reply> replies) {
        List<Double> sorted = replies.stream()
            .map(Reply::elapsedMillis)
            .sorted()
            .toList();
        return Map.of("requests", replies.size(), "concurrentClients", 20,
            "p50Millis", sorted.get((int) Math.ceil(sorted.size() * .50) - 1),
            "p95Millis", sorted.get((int) Math.ceil(sorted.size() * .95) - 1),
            "maxMillis", sorted.getLast(), "http200", replies.stream()
                .filter(reply -> reply.httpStatus() == 200)
                .count());
    }

    private static Instant instant(Reply reply, String field) {
        return Instant.parse(reply.value("/snapshot/" + field));
    }

    private static void pauseUntil(Instant instant) throws InterruptedException {
        long millis;
        while ((millis = Duration.between(Instant.now(), instant).toMillis()) > 0) {
            Thread.sleep(Math.min(1000, millis));
        }
    }

    private static int snapshots() {
        try {
            return runtime.snapshots();
        }
        catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
