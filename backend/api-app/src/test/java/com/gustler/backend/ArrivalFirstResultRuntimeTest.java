package com.gustler.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.arrival.profiling.TimingAgentJar;
import com.gustler.backend.arrival.support.ArrivalRuntime;
import com.gustler.backend.arrival.support.FakeGbisArrivalServer;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 실제 두 Boot JAR·PostgreSQL에서 현행 클라이언트의 Date/Age 폴링을 그대로 비교한다. */
@EnabledIfSystemProperty(named = "arrival.firstResultProfile", matches = "true")
class ArrivalFirstResultRuntimeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static final Path DIRECTORY = Path.of("build/reports/arrival-first-result").toAbsolutePath();
    private final List<Map<String, Object>> results = new ArrayList<>();
    private final List<Map<String, Object>> requests = new ArrayList<>();
    private final Map<String, JsonNode> traces = new HashMap<>();

    private record Reply(JsonNode body, double millis, double receivedNanos, long serverClockMillis) { }
    private record Outcome(boolean firstReady, double firstHttpMs, double usableMs, int httpCalls) { }

    @Test
    @Timeout(300)
    void 최초_결과_대기의_효과와_느린상류_동시요청_비용을_실행_JAR로_비교한다() throws Exception {
        Files.createDirectories(DIRECTORY);
        Path agent = TimingAgentJar.create(DIRECTORY.resolve("timing-agent.jar"));
        try {
            for (int waitMillis : List.of(0, 500)) profile(waitMillis, agent);
        } finally {
            Files.writeString(DIRECTORY.resolve("comparison.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "generatedAt", Instant.now().toString(), "scope", "local API/worker Boot JARs, PostgreSQL18, controlled synthetic upstream",
                "realPublicApiCalls", 0, "timings", "40s refresh, 60s expiry; current frontend Date/Age polling unchanged",
                "notes", "No SLA assertions; >=1s failure boundaries and response semantics are tested separately. API SQL counts include revalidating the route version after a completed wait.",
                "cases", results)));
            try (var file = Files.newBufferedWriter(DIRECTORY.resolve("requests.jsonl"))) {
                for (var row : requests) {
                    String id = (String) row.get("id");
                    JsonNode trace = traces.get(id);
                    if (trace != null) row.put("apiSqlExecutions", trace.at("/calls/jdbc.execute").intValue());
                    file.write(JSON.writeValueAsString(row)); file.newLine();
                }
            }
        }
    }

    private void profile(final int waitMillis, Path agent) throws Exception {
        Path trace = DIRECTORY.resolve("api-" + waitMillis + ".jsonl");
        try (var runtime = new ArrivalRuntime(List.of("-javaagent:" + agent + "=" + trace),
            Map.of("ARRIVALS_FIRST_RESULT_WAIT", waitMillis + "ms"))) {
            runtime.startWorker(true, Map.of());
            Reply starting = get(URI.create(runtime.arrivalsUri().toString()), null);
            if ("STARTING".equals(starting.body().at("/refresh/reason").asString())) pauseToSuggested(starting);
            Reply warm = get(runtime.arrivalsUri(), null);
            if (!"READY".equals(warm.body().path("status").asString())) {
                pauseToSuggested(warm); warm = get(runtime.arrivalsUri(), null);
            }
            assertThat(warm.body().path("status").asString()).isEqualTo("READY");
            String version = warm.body().at("/routes/0/targetVisit/referenceVersionId").asString();
            int stationIndex = 1;
            int fastFirstReady = 0;
            for (int delay : List.of(100, 300, 800)) {
                for (int repeat = 0; repeat < 2; repeat++) {
                    String station = FakeGbisArrivalServer.STATIONS.get(stationIndex);
                    runtime.source.plan(station, FakeGbisArrivalServer.Plan.slow(Duration.ofMillis(delay)));
                    final int sourceBefore = runtime.source.calls();
                    final int reservationsBefore = runtime.reservations();
                    String prefix = "wait" + waitMillis + "-delay" + delay + "-r" + repeat;
                    Outcome outcome = untilReady(uri(runtime, station, version, stationIndex + 1), prefix);
                    assertThat(runtime.source.calls() - sourceBefore).isEqualTo(1);
                    assertThat(runtime.reservations() - reservationsBefore).isEqualTo(1);
                    if (delay <= 300 && outcome.firstReady()) fastFirstReady++;
                    results.add(result(waitMillis, delay, 1, List.of(outcome), prefix, 1));
                    stationIndex++;
                }
            }
            if (waitMillis > 0) assertThat(fastFirstReady).isGreaterThan(0);
            for (int concurrency : List.of(20, 100)) {
                String station = FakeGbisArrivalServer.STATIONS.get(stationIndex);
                runtime.source.plan(station, FakeGbisArrivalServer.Plan.slow(Duration.ofMillis(300)));
                String prefix = "wait" + waitMillis + "-c" + concurrency;
                final int sourceBefore = runtime.source.calls();
                URI target = uri(runtime, station, version, stationIndex + 1);
                List<Outcome> outcomes = burst(target, prefix, concurrency);
                assertThat(runtime.source.calls() - sourceBefore).isEqualTo(1);
                results.add(result(waitMillis, 300, concurrency, outcomes, prefix, 1));
                stationIndex++;
            }
            // 정상 빈 수신도 완료로 처리하고 예측을 만들어 내지 않는다.
            String emptyStation = FakeGbisArrivalServer.STATIONS.get(stationIndex);
            runtime.source.plan(emptyStation, new FakeGbisArrivalServer.Plan(200,
                "{\"response\":{\"msgHeader\":{\"resultCode\":0},\"msgBody\":{\"busArrivalList\":[]}}}", null, Duration.ofMillis(100), null));
            String emptyPrefix = "wait" + waitMillis + "-empty";
            Reply empty = get(uri(runtime, emptyStation, version, ++stationIndex), emptyPrefix + "-first");
            if (waitMillis > 0) assertThat(empty.body().path("status").asString()).isEqualTo("NO_PREDICTION");
            assertThat(empty.body().path("routes").size()).isZero();
            String failedStation = FakeGbisArrivalServer.STATIONS.get(stationIndex);
            runtime.source.plan(failedStation, FakeGbisArrivalServer.Plan.error(500, null));
            Reply failed = get(uri(runtime, failedStation, version, stationIndex + 1), "wait" + waitMillis + "-upstream-error-first");
            assertThat(failed.body().path("status").asString()).isEqualTo("UNAVAILABLE");
            assertThat(failed.body().path("routes").size()).isZero();
            loadTraces(trace);
            assertThat(runtime.source.unexpected()).isEmpty();
        }
    }

    private static URI uri(ArrivalRuntime runtime, String station, String version, final int sequence) {
        String base = runtime.arrivalsUri().toString().replace(FakeGbisArrivalServer.STATIONS.getFirst(), station);
        return URI.create(base + "?routeId=204000057&referenceVersionId=" + version + "&stopSequence=" + sequence);
    }

    private List<Outcome> burst(URI uri, String prefix, final int concurrency) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(concurrency)) {
            List<Future<Outcome>> calls = new ArrayList<>();
            for (int i=0;i<concurrency;i++) {
                final int index = i;
                calls.add(executor.submit(() -> { start.await(); return untilReady(uri, prefix + "-" + index); }));
            }
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (var call : calls) outcomes.add(call.get(12, TimeUnit.SECONDS));
            return outcomes;
        }
    }

    private Outcome untilReady(URI uri, String prefix) throws Exception {
        final long began = System.nanoTime();
        Reply reply = get(uri, prefix + "-first");
        final double firstHttp = reply.millis();
        final boolean firstReady = "READY".equals(reply.body().path("status").asString());
        int calls = 1;
        while (!"READY".equals(reply.body().path("status").asString())) {
            assertThat(calls).as("수집이 끝나지 않음: %s", prefix).isLessThan(4);
            pauseToSuggested(reply);
            reply = get(uri, prefix + "-poll" + calls++);
        }
        assertThat(reply.body().at("/routes/0/targetVisit/matchStatus").asString()).isEqualTo("MATCHED");
        assertThat(reply.body().at("/routes/0/arrivals/0/eta/status").asString()).isEqualTo("AVAILABLE");
        assertThat(Duration.between(Instant.parse(reply.body().at("/snapshot/receivedAt").asString()),
            Instant.parse(reply.body().at("/snapshot/staleAt").asString()))).isEqualTo(Duration.ofSeconds(60));
        return new Outcome(firstReady, firstHttp, (System.nanoTime()-began)/1e6, calls);
    }

    private synchronized void loadTraces(Path trace) throws Exception {
        // 완료된 응답의 filter가 계측 로그를 기록할 시간을 둔다.
        Thread.sleep(50);
        for (String line : Files.readAllLines(trace)) {
            if (line.endsWith("}")) { JsonNode row = JSON.readTree(line); traces.put(row.path("id").asString(), row); }
        }
        for (var result : results) {
            String prefix = (String) result.get("requestPrefix");
            if (!prefix.startsWith("wait" + result.get("waitMillis"))) continue;
            final int sql = requests.stream().filter(r -> ((String) r.get("id")).startsWith(prefix))
                .mapToInt(r -> traces.get((String) r.get("id")).at("/calls/jdbc.execute").intValue()).sum();
            result.put("apiSqlExecutions", sql);
        }
    }

    private Reply get(URI uri, String id) throws Exception {
        var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5));
        if (id != null) builder.header("X-ETA-Measure", id);
        final long began = System.nanoTime();
        var response = HTTP.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
        final long received = System.nanoTime();
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        final long served = response.headers().firstValue("date")
            .map(value -> ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli())
            .orElseGet(() -> Instant.parse(body.path("generatedAt").asString()).toEpochMilli())
            + 1000L * response.headers().firstValue("age").map(Long::parseLong).orElse(0L);
        if (id != null) synchronized (this) {
            Map<String, Object> row = new LinkedHashMap<>(); row.put("id", id); row.put("httpMs", (received-began)/1e6);
            row.put("status", body.path("status").asString()); row.put("refreshStatus", body.at("/refresh/status").asString());
            requests.add(row);
        }
        return new Reply(body, (received-began)/1e6, received, served);
    }

    private static void pauseToSuggested(Reply reply) throws InterruptedException {
        final long suggested = Instant.parse(reply.body().at("/refresh/suggestedRefreshAt").asString()).toEpochMilli();
        final long elapsed = (long) ((System.nanoTime()-reply.receivedNanos())/1e6);
        Thread.sleep(Math.max(1000, suggested-reply.serverClockMillis()-elapsed));
    }

    private static Map<String, Object> result(final int wait, final int delay, final int concurrency,
        List<Outcome> outcomes, String prefix, final int sourceCalls) {
        Map<String, Object> row = new LinkedHashMap<>(); row.put("waitMillis", wait); row.put("upstreamDelayMs", delay);
        row.put("clients", concurrency); row.put("requestPrefix", prefix); row.put("sourceCalls", sourceCalls);
        row.put("firstReady", outcomes.stream().filter(Outcome::firstReady).count());
        row.put("firstHttpP50Ms", percentile(outcomes.stream().map(Outcome::firstHttpMs).toList(), .5));
        row.put("firstHttpP95Ms", percentile(outcomes.stream().map(Outcome::firstHttpMs).toList(), .95));
        row.put("timeToReadyP50Ms", percentile(outcomes.stream().map(Outcome::usableMs).toList(), .5));
        row.put("timeToReadyP95Ms", percentile(outcomes.stream().map(Outcome::usableMs).toList(), .95));
        row.put("httpCalls", outcomes.stream().mapToInt(Outcome::httpCalls).sum()); return row;
    }

    private static double percentile(List<Double> values, final double percentile) {
        List<Double> sorted = values.stream().sorted().toList();
        return sorted.get((int) Math.ceil(sorted.size()*percentile)-1);
    }
}
