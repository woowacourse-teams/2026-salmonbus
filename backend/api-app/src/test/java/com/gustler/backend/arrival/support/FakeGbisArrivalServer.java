package com.gustler.backend.arrival.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 실제 HTTP 요청을 받되 공공 API로는 연결하지 않는 테스트 서버. */
public final class FakeGbisArrivalServer implements AutoCloseable {
    public static final List<String> STATIONS = java.util.stream.IntStream.range(0, 12)
        .mapToObj(index -> Integer.toString(206000637 + index)).toList();
    private static final String PATH = "/busarrivalservice/v2/getBusArrivalListv2";

    public record Plan(int status, String body, String retryAfter, Duration delay, CountDownLatch gate) {
        public static Plan held() {
            return new Plan(200, null, null, Duration.ZERO, new CountDownLatch(1));
        }

        public static Plan slow(Duration delay) {
            return new Plan(200, null, null, delay, null);
        }

        public static Plan error(int status, String retryAfter) {
            return new Plan(status, "", retryAfter, Duration.ZERO, null);
        }

        public void release() {
            if (gate != null) gate.countDown();
        }
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Plan> plans = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private final List<String> unexpected = new CopyOnWriteArrayList<>();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger maximumActive = new AtomicInteger();

    public FakeGbisArrivalServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", this::respond);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void plan(String station, Plan plan) {
        plans.put(station, plan);
    }

    public int calls() {
        return calls.values().stream().mapToInt(AtomicInteger::get).sum();
    }

    public int maximumActive() {
        return maximumActive.get();
    }

    public List<String> unexpected() {
        return List.copyOf(unexpected);
    }

    public void reset() {
        plans.values().forEach(Plan::release);
        if (active.get() != 0) throw new IllegalStateException("이전 가짜 응답이 아직 진행 중이다");
        plans.clear();
        calls.clear();
        unexpected.clear();
        maximumActive.set(0);
    }

    private void respond(HttpExchange exchange) throws IOException {
        Map<String, String> query = Arrays.stream(exchange.getRequestURI().getRawQuery().split("&"))
            .map(pair -> pair.split("=", 2)).collect(java.util.stream.Collectors.toMap(
                pair -> pair[0], pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
        String station = query.get("stationId");
        if (!PATH.equals(exchange.getRequestURI().getPath()) || !STATIONS.contains(station)
            || !query.getOrDefault("serviceKey", "").startsWith("fake-arrival-")) {
            unexpected.add(exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
            return;
        }
        calls.computeIfAbsent(station, ignored -> new AtomicInteger()).incrementAndGet();
        maximumActive.accumulateAndGet(active.incrementAndGet(), Math::max);
        Plan plan = plans.getOrDefault(station, new Plan(200, null, null, Duration.ZERO, null));
        try (exchange) {
            if (plan.gate() != null && !plan.gate().await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("가짜 응답 대기 한도 초과");
            }
            Thread.sleep(plan.delay().toMillis());
            byte[] body = (plan.body() == null ? normalBody(station) : plan.body()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (plan.retryAfter() != null) exchange.getResponseHeaders().set("Retry-After", plan.retryAfter());
            exchange.sendResponseHeaders(plan.status(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) exchange.getResponseBody().write(body);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException disconnected) {
            // 의도적인 5초 타임아웃 시나리오에서는 Worker가 연결을 먼저 닫는다.
        } finally {
            active.decrementAndGet();
        }
    }

    public void release() {
        plans.values().forEach(Plan::release);
    }

    public boolean idle() {
        return active.get() == 0;
    }

    private static String normalBody(String station) {
        int order = STATIONS.indexOf(station) + 1;
        return """
            {"response":{"msgHeader":{"resultCode":0,"queryTime":"synthetic"},"msgBody":{"busArrivalList":[
              {"stationId":"%s","routeId":"204000057","routeName":"3330","staOrder":%d,"routeTypeCd":12,
               "vehId1":"vehicle-a","predictTimeSec1":180,"predictTime1":3,"locationNo1":3,"remainSeatCnt1":0},
              {"stationId":"%s","routeId":"204000155","routeName":"1650","staOrder":%d,"routeTypeCd":12,
               "vehId1":"vehicle-b","predictTimeSec1":240,"predictTime1":4,"locationNo1":4,"remainSeatCnt1":10}
            ]}}}
            """.formatted(station, order, station, order);
    }

    @Override
    public void close() {
        release();
        server.stop(0);
        executor.shutdownNow();
        executor.close();
    }
}
