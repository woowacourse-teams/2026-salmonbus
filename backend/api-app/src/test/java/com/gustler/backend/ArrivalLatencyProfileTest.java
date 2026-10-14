package com.gustler.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.arrival.profiling.TimingAgentJar;
import com.gustler.backend.arrival.support.ArrivalRuntime;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 실행: ./gradlew :api-app:test --tests com.gustler.backend.ArrivalLatencyProfileTest -ParrivalProfile=true
 * 성능 수치에 합격선을 두지 않는다. 구간 계측이 실제 요청과 대응되는지와 응답 정합성만 검사한다.
 */
@EnabledIfSystemProperty(named = "arrival.profile", matches = "true")
class ArrivalLatencyProfileTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static final Path DIRECTORY = Path.of("build/reports/arrival-latency").toAbsolutePath();
    private static final List<Map<String, Object>> CASES = new ArrayList<>();
    private static final List<Map<String, Object>> RAW = new ArrayList<>();
    private static final String[] REQUIRED = {"pool.acquire", "jdbc.execute", "repository.visits", "repository.latest",
        "snapshot.decode", "query.service", "response.dto", "response.write", "server.filter"};

    private record ClientSample(String id, double httpMillis, double parseMillis, double totalMillis, int bytes) { }

    @Test
    @Timeout(240)
    void 신선한_ETA_조회에서_단일과_동시_요청의_구간별_시간을_측정한다() throws Exception {
        Files.createDirectories(DIRECTORY);
        CASES.clear();
        RAW.clear();
        Path agent = TimingAgentJar.create(DIRECTORY.resolve("arrival-timing-agent.jar"));
        try {
            // 별도 계측 없이 기존 통합 검증과 같은 짧은 요청 묶음의 변동도 남긴다.
            try (var runtime = new ArrivalRuntime()) {
                runtime.seedFreshReceipt(2);
                for (int round = 1; round <= 3; round++) {
                    List<ClientSample> samples = requests(runtime.arrivalsUri(), "baseline" + round, 100, 20, false);
                    summarize("unprofiled-short100-r" + round, 5, 20, samples, Map.of(), false);
                }
                requests(runtime.arrivalsUri(), "baseline-warmup", 1000, 20, false);
                summarize("unprofiled-single300", 5, 1,
                    requests(runtime.arrivalsUri(), "baseline-single", 300, 1, false), Map.of(), false);
                for (int round = 1; round <= 3; round++) {
                    summarize("unprofiled-c20-r" + round, 5, 20,
                        requests(runtime.arrivalsUri(), "baseline-c20-r" + round, 1000, 20, false), Map.of(), false);
                }
                assertThat(runtime.source.calls()).isZero();
            }
            profile(agent, 5);
            profile(agent, 20);
        } finally {
            var report = Map.of("generatedAt", Instant.now().toString(), "scope", "local Boot JAR / PostgreSQL 18",
                "realPublicApiCalls", 0, "fixture", "synthetic fresh receipt, refresh in 1h to isolate the read path",
                "jvm", "Java 21; 256MB heap; ActiveProcessorCount=2 (processor-count hint)",
                "notes", List.of("Instrumented stages are inclusive; do not add their percentiles.",
                    "pool.acquire includes getConnection overhead, not only blocking.",
                    "jdbc.execute includes driver/network/DB work; it is not DB engine time alone.",
                    "client residual includes connector queue, HTTP transport and client scheduling.",
                    "Pool 20 is a diagnostic comparison, not a production configuration change."),
                "cases", CASES);
            Files.writeString(DIRECTORY.resolve("summary.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            try (var output = Files.newBufferedWriter(DIRECTORY.resolve("requests.jsonl"))) {
                for (var sample : RAW) { output.write(JSON.writeValueAsString(sample)); output.newLine(); }
            }
        }
    }

    private static void profile(Path agent, int pool) throws Exception {
        Path trace = DIRECTORY.resolve("pool-" + pool + ".jsonl");
        try (var runtime = new ArrivalRuntime(List.of("-javaagent:" + agent + "=" + trace),
            Map.of("SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE", Integer.toString(pool)))) {
            runtime.seedFreshReceipt(2);
            List<ClientSample> first = requests(runtime.arrivalsUri(), "p" + pool + "first", 100, 20, true);
            Map<String, JsonNode> pilot = traces(trace, first);
            for (var row : pilot.values()) {
                for (String name : REQUIRED) assertThat(row.path("nanos").has(name))
                    .as("계측 누락: %s, 요청=%s", name, row.path("id")).isTrue();
            }
            summarize("pool" + pool + "-first100", pool, 20, first, pilot, true);

            requests(runtime.arrivalsUri(), "warmup", 1000, 20, false);
            measure(runtime.arrivalsUri(), "pool" + pool + "-single300", pool, 300, 1, trace);
            for (int round = 1; round <= 3; round++) {
                measure(runtime.arrivalsUri(), "pool" + pool + "-c20-r" + round, pool, 1000, 20, trace);
            }
            if (pool == 5) {
                runtime.seedFreshReceipt(27);
                URI selected = URI.create(runtime.arrivalsUri() + "?routeId=204000057");
                requests(selected, "warm27", 500, 20, false);
                measure(selected, "pool5-routes27-selected-single", pool, 300, 1, trace);
                measure(selected, "pool5-routes27-selected-c20", pool, 1000, 20, trace);
            }
            assertThat(runtime.source.calls()).isZero();
        }
    }

    private static void measure(URI uri, String name, int pool, int count, int concurrency, Path trace) throws Exception {
        List<ClientSample> samples = requests(uri, name, count, concurrency, true);
        summarize(name, pool, concurrency, samples, traces(trace, samples), true);
    }

    private static List<ClientSample> requests(URI uri, String prefix, int count, int concurrency, boolean trace) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ClientSample>> futures = new ArrayList<>();
        try (var clients = Executors.newFixedThreadPool(concurrency)) {
            for (int index = 0; index < count; index++) {
                String id = prefix + "-" + index;
                futures.add(clients.submit(() -> {
                    start.await();
                    long began = System.nanoTime();
                    var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10));
                    if (trace) builder.header("X-ETA-Measure", id);
                    var response = HTTP.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
                    long received = System.nanoTime();
                    JsonNode body = JSON.readTree(response.body());
                    long parsed = System.nanoTime();
                    assertThat(response.statusCode()).isEqualTo(200);
                    assertThat(body.path("status").asString()).isEqualTo("READY");
                    return new ClientSample(id, (received - began) / 1e6, (parsed - received) / 1e6,
                        (parsed - began) / 1e6, response.body().length());
                }));
            }
            start.countDown();
            List<ClientSample> samples = new ArrayList<>();
            for (var future : futures) samples.add(future.get(20, TimeUnit.SECONDS));
            return samples;
        }
    }

    private static Map<String, JsonNode> traces(Path path, List<ClientSample> samples) throws Exception {
        Map<String, JsonNode> values = new HashMap<>();
        for (int attempt = 0; attempt < 40; attempt++) {
            values.clear();
            for (String line : Files.readAllLines(path)) {
                if (line.endsWith("}")) {
                    var node = JSON.readTree(line);
                    values.put(node.path("id").asString(), node);
                }
            }
            if (samples.stream().allMatch(sample -> values.containsKey(sample.id()))) return values;
            Thread.sleep(50);
        }
        throw new AssertionError("HTTP 응답과 대응되는 서버 계측 기록이 부족하다");
    }

    private static void summarize(String name, int pool, int concurrency, List<ClientSample> clients,
                                   Map<String, JsonNode> traces, boolean instrumented) {
        Map<String, List<Double>> measures = new LinkedHashMap<>();
        for (ClientSample client : clients) {
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("case", name);
            raw.put("id", client.id());
            raw.put("client.total", client.totalMillis());
            raw.put("client.http", client.httpMillis());
            raw.put("client.parse", client.parseMillis());
            if (instrumented) {
                JsonNode server = traces.get(client.id());
                for (String section : REQUIRED) raw.put(section, server.path("nanos").path(section).longValue() / 1e6);
                raw.put("unattributed", client.totalMillis() - client.parseMillis() - (double) raw.get("server.filter"));
                assertThat(server.at("/calls/pool.acquire").intValue()).isEqualTo(2);
                assertThat(server.at("/calls/jdbc.execute").intValue()).isEqualTo(2);
                assertThat(server.at("/calls/snapshot.decode").intValue()).isEqualTo(1);
            }
            raw.forEach((key, value) -> {
                if (value instanceof Double number) measures.computeIfAbsent(key, ignored -> new ArrayList<>()).add(number);
            });
            RAW.add(raw);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", name);
        summary.put("requests", clients.size());
        summary.put("concurrentClients", concurrency);
        summary.put("pool", pool);
        summary.put("instrumented", instrumented);
        summary.put("responseCharacters", clients.getFirst().bytes());
        Map<String, Object> stats = new LinkedHashMap<>();
        measures.forEach((key, values) -> stats.put(key, stats(values)));
        summary.put("milliseconds", stats);
        CASES.add(summary);
        System.out.printf("arrival_profile case=%s client=%s server=%s pool=%s jdbc=%s%n", name,
            stats.get("client.total"), stats.get("server.filter"), stats.get("pool.acquire"), stats.get("jdbc.execute"));
    }

    private static Map<String, Double> stats(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        return Map.of("mean", values.stream().mapToDouble(Double::doubleValue).average().orElseThrow(),
            "p50", sorted.get((int) Math.ceil(sorted.size() * .50) - 1),
            "p95", sorted.get((int) Math.ceil(sorted.size() * .95) - 1), "max", sorted.getLast());
    }
}
