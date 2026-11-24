package com.gustler.backend.api.board.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;

import com.gustler.backend.api.route.RouteId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.WebAsyncManager;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class WatchStreamRegistryTest {
    private static final int RACE_ROUNDS = 400;
    private static final String ROUTE = "123456789";
    private static final String OTHER_ROUTE = "123456780";
    private static final RouteId ROUTE_ID = new RouteId(ROUTE);
    private static final String STREAM = "/api/v1/routes/" + ROUTE + "/watch/events";
    private static final String STREAM_HEADER = "X-Stream";
    private static final String FIRST_FRAME = "retry:\\d+\\n: connected\\n\\n";
    private static final String PING = "event:ping\ndata:1\n\n";
    /** 초가 0 이라서 {@code toString()} 과 JSON 의 글자가 다르다. */
    private static final OffsetDateTime X = OffsetDateTime.parse("2026-08-27T11:59:00+09:00");
    private static final OffsetDateTime Y = OffsetDateTime.parse("2026-08-27T11:59:21.412935+09:00");
    private static final OffsetDateTime EARLIER = OffsetDateTime.parse("2026-08-27T11:58:40+09:00");
    private static final Pattern OBSERVED_AT = Pattern.compile("\"observedAt\":\"([^\"]+)\"");

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final BreakableResponses breakable = new BreakableResponses();
    private WatchStreamRegistry registry;
    private MockMvc mvc;

    @AfterEach
    void tearDown() {
        registry.close();
    }

    @Test
    void 전송에_실패한_연결만_빼고_완료시키지_않으며_나머지에는_계속_쓴다() throws Exception {
        // given
        createRegistry(2);
        MvcResult broken = open("broken");
        MvcResult alive = open("alive");
        breakable.breakResponse("broken");

        // when
        registry.ping();

        // then
        assertThat(connections()).isEqualTo(1);
        assertThat(alive.getResponse().getContentAsString()).matches(FIRST_FRAME + PING);
        assertThat(asyncManagerOf(broken).hasConcurrentResult()).isFalse();
    }

    @Test
    void 상한에_닿으면_204이고_끝난_연결의_자리는_오류와_완료_알림이_겹쳐도_하나만_난다() throws Exception {
        // given
        createRegistry(2);
        MvcResult ended = open("ended");
        open("other");
        MvcResult overCap = open("over-cap");

        // when
        MockAsyncContext async = (MockAsyncContext) ended.getRequest().getAsyncContext();
        for (AsyncListener listener : async.getListeners()) {
            listener.onError(new AsyncEvent(async, new IOException("Broken pipe")));
        }
        async.complete();

        // then
        assertThat(overCap.getResponse().getStatus()).isEqualTo(204);
        assertThat(overCap.getRequest().isAsyncStarted()).isFalse();
        assertThat(connections()).isEqualTo(1);
        assertThat(open("refill").getResponse().getStatus()).isEqualTo(200);
        assertThat(open("over-cap-again").getResponse().getStatus()).isEqualTo(204);
    }

    @Test
    void 닫으면_열린_연결을_모두_끝내고_새_연결을_거절하며_다시_닫아도_같다() throws Exception {
        // given
        createRegistry(2);
        MvcResult first = open("first");
        MvcResult second = open("second");

        // when
        registry.close();

        // then
        for (MvcResult stream : new MvcResult[] {first, second}) {
            WebAsyncManager async = asyncManagerOf(stream);
            assertThat(async.hasConcurrentResult()).isTrue();
            assertThat(async.getConcurrentResult()).isNull();
        }
        assertThat(connections()).isZero();
        assertThat(open("after-close").getResponse().getStatus()).isEqualTo(204);
        registry.close();
        registry.ping();
        registry.observed(ROUTE_ID, X);
        assertThat(connections()).isZero();
        assertThat(registry.watchedRoutes()).isEmpty();
        assertThat(first.getResponse().getContentAsString()).matches(FIRST_FRAME);
    }

    @Test
    void 등록_직후에_닫혀도_그_연결은_첫_쓰기를_받고_곧바로_끝난다() throws Exception {
        // given
        createRegistry(2);
        LateController late = new LateController(registry.open(new RouteId("123456789")).orElseThrow());
        registry.close();

        // when
        MvcResult stream = MockMvcBuilders.standaloneSetup(late).build().perform(get(LateController.PATH)).andReturn();

        // then
        WebAsyncManager async = asyncManagerOf(stream);
        assertThat(stream.getResponse().getContentAsString()).matches(FIRST_FRAME);
        assertThat(async.hasConcurrentResult()).isTrue();
        assertThat(async.getConcurrentResult()).isNull();
    }

    @Test
    void HEAD_요청은_스트림을_열지_않고_헤더만_답한다() throws Exception {
        // given
        createRegistry(2);

        // when
        MvcResult described = mvc.perform(head(STREAM)).andReturn();

        // then
        assertThat(described.getResponse().getStatus()).isEqualTo(200);
        assertThat(described.getResponse().getContentType()).isEqualTo("text/event-stream");
        assertThat(described.getResponse().getHeader("Cache-Control")).isEqualTo("no-store, no-transform");
        assertThat(described.getRequest().isAsyncStarted()).isFalse();
        assertThat(connections()).isZero();
    }

    @Test
    void 노선의_첫_연결은_관측_없이_시작하고_처음_알린_관측을_받으며_뒤에_온_연결은_첫_쓰기로_받는다() throws Exception {
        // given
        createRegistry(4);
        MvcResult first = open("first");

        // when
        registry.observed(ROUTE_ID, X);
        MvcResult later = open("later");
        MvcResult other = open(OTHER_ROUTE, "other");

        // then
        assertThat(first.getResponse().getContentAsString())
            .matches(FIRST_FRAME + Pattern.quote(signal("2026-08-27T11:59:00+09:00")));
        assertThat(later.getResponse().getContentAsString())
            .matches(FIRST_FRAME + Pattern.quote(signal("2026-08-27T11:59:00+09:00")));
        assertThat(other.getResponse().getContentAsString()).matches(FIRST_FRAME);
        assertThat(registry.watchedRoutes()).containsExactlyInAnyOrder(ROUTE_ID, new RouteId(OTHER_ROUTE));
        assertThat(signals("connect")).isEqualTo(1);
        assertThat(signals("change")).isEqualTo(1);
    }

    @Test
    void 같은_관측을_다시_알리면_보내지_않고_달라진_관측은_과거_값이어도_그_노선의_연결에만_보낸다() throws Exception {
        // given
        createRegistry(4);
        MvcResult first = open("first");
        MvcResult second = open("second");
        MvcResult other = open(OTHER_ROUTE, "other");

        // when
        registry.observed(ROUTE_ID, X);
        registry.observed(ROUTE_ID, X);
        registry.observed(ROUTE_ID, X.withOffsetSameInstant(ZoneOffset.UTC));
        registry.observed(ROUTE_ID, Y);
        registry.observed(ROUTE_ID, EARLIER);

        // then
        for (MvcResult stream : new MvcResult[] {first, second}) {
            assertThat(observedAt(stream)).containsExactly(
                "2026-08-27T11:59:00+09:00", "2026-08-27T11:59:21.412935+09:00", "2026-08-27T11:58:40+09:00");
        }
        assertThat(other.getResponse().getContentAsString()).matches(FIRST_FRAME);
        assertThat(signals("change")).isEqualTo(6);
    }

    @Test
    void 마지막_연결이_떠난_노선은_잊어서_다시_온_연결은_같은_관측을_알림으로_받는다() throws Exception {
        // given
        createRegistry(4);
        MvcResult left = open("left");
        registry.observed(ROUTE_ID, X);
        end(left);
        registry.observed(ROUTE_ID, Y);

        // when
        MvcResult returned = open("returned");
        String greeting = returned.getResponse().getContentAsString();
        registry.observed(ROUTE_ID, X);

        // then
        assertThat(greeting).matches(FIRST_FRAME);
        assertThat(returned.getResponse().getContentAsString())
            .matches(FIRST_FRAME + Pattern.quote(signal("2026-08-27T11:59:00+09:00")));
        assertThat(registry.watchedRoutes()).containsExactly(ROUTE_ID);
    }

    @Test
    void 알리는_중에_전송이_실패한_연결만_빼고_완료시키지_않으며_나머지에는_보낸다() throws Exception {
        // given
        createRegistry(4);
        MvcResult broken = open("broken");
        MvcResult alive = open("alive");
        breakable.breakResponse("broken");

        // when
        registry.observed(ROUTE_ID, X);
        registry.ping();

        // then
        assertThat(connections()).isEqualTo(1);
        assertThat(alive.getResponse().getContentAsString())
            .matches(FIRST_FRAME + Pattern.quote(signal("2026-08-27T11:59:00+09:00") + PING));
        assertThat(asyncManagerOf(broken).hasConcurrentResult()).isFalse();
        assertThat(signals("change")).isEqualTo(1);
    }

    @Test
    void 연결이_열리는_동안_관측이_계속_바뀌어도_연결마다_빠짐과_중복_없이_이어_받는다() throws Exception {
        // given
        createRegistry(4);
        OffsetDateTime base = OffsetDateTime.parse("2026-08-27T12:00:00+09:00");
        AtomicLong next = new AtomicLong();
        MvcResult permanent = open("permanent");
        registry.observed(ROUTE_ID, base.plusSeconds(next.getAndIncrement()));
        long burstNanos = 2 * fastestOpenNanos();
        ExecutorService threads = Executors.newFixedThreadPool(2);
        List<String> broken = new ArrayList<>();

        // when
        try {
            for (int round = 0; round < RACE_ROUNDS; round++) {
                CyclicBarrier together = new CyclicBarrier(2);
                Future<MvcResult> opening = threads.submit(() -> {
                    together.await();
                    return open("round");
                });
                Future<Long> reporting = threads.submit(() -> {
                    together.await();
                    long until = System.nanoTime() + burstNanos;
                    long last;
                    do {
                        last = next.getAndIncrement();
                        registry.observed(ROUTE_ID, base.plusSeconds(last));
                    } while (System.nanoTime() < until);
                    return last;
                });
                MvcResult opened = opening.get(10, TimeUnit.SECONDS);
                long last = reporting.get(10, TimeUnit.SECONDS);
                List<Long> seen = observedAt(opened).stream()
                    .map(value -> Duration.between(base, OffsetDateTime.parse(value)).toSeconds())
                    .toList();
                if (!isUnbrokenRunEndingAt(seen, last)) {
                    broken.add("round " + round + ": " + seen + " last=" + last);
                }
                end(opened);
            }
        } finally {
            threads.shutdownNow();
        }

        // then
        assertThat(broken).isEmpty();
        assertThat(observedAt(permanent)).hasSize((int) next.get());
    }

    private void createRegistry(int maxConnections) {
        registry = new WatchStreamRegistry(
            new WatchStreamSettings(maxConnections, Duration.ofHours(1), Duration.ofMinutes(30), Duration.ofSeconds(1),
                Duration.ofSeconds(1)), meters);
        mvc = MockMvcBuilders.standaloneSetup(new WatchStreamController(registry)).addFilters(breakable).build();
    }

    private MvcResult open(String stream) throws Exception {
        return open(ROUTE, stream);
    }

    private MvcResult open(String routeId, String stream) throws Exception {
        return mvc.perform(get("/api/v1/routes/" + routeId + "/watch/events").header(STREAM_HEADER, stream)).andReturn();
    }

    private static void end(MvcResult stream) {
        ((MockAsyncContext) stream.getRequest().getAsyncContext()).complete();
    }

    private long fastestOpenNanos() throws Exception {
        long fastest = Long.MAX_VALUE;
        for (int warm = 0; warm < 200; warm++) {
            long started = System.nanoTime();
            MvcResult opened = open("warm");
            fastest = Math.min(fastest, System.nanoTime() - started);
            end(opened);
        }
        return fastest;
    }

    private static boolean isUnbrokenRunEndingAt(List<Long> seen, long last) {
        if (seen.isEmpty() || seen.getLast() != last) {
            return false;
        }
        for (int index = 1; index < seen.size(); index++) {
            if (seen.get(index) - seen.get(index - 1) != 1) {
                return false;
            }
        }
        return true;
    }

    private static List<String> observedAt(MvcResult stream) throws Exception {
        Matcher values = OBSERVED_AT.matcher(stream.getResponse().getContentAsString());
        List<String> found = new ArrayList<>();
        while (values.find()) {
            found.add(values.group(1));
        }
        return found;
    }

    private static String signal(String observedAt) {
        return "event:observation\ndata:{\"observedAt\":\"" + observedAt + "\"}\n\n";
    }

    private double signals(String cause) {
        return meters.counter("salmonbus.watch.stream.signals", "cause", cause).count();
    }

    private double connections() {
        return meters.get("salmonbus.watch.stream.connections").gauge().value();
    }

    private static WebAsyncManager asyncManagerOf(MvcResult stream) {
        return WebAsyncUtils.getAsyncManager(stream.getRequest());
    }

    @RestController
    static class LateController {
        static final String PATH = "/late";

        private final SseEmitter pending;

        LateController(SseEmitter pending) {
            this.pending = pending;
        }

        @GetMapping(PATH)
        SseEmitter late() {
            return pending;
        }
    }

    static final class BreakableResponses implements Filter {
        private final Map<String, AtomicBoolean> broken = new ConcurrentHashMap<>();

        void breakResponse(String stream) {
            broken.get(stream).set(true);
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
            String stream = ((HttpServletRequest) request).getHeader(STREAM_HEADER);
            if (stream == null) {
                chain.doFilter(request, response);
                return;
            }
            AtomicBoolean isBroken = broken.computeIfAbsent(stream, key -> new AtomicBoolean());
            chain.doFilter(request, new BreakableResponse((HttpServletResponse) response, isBroken));
        }
    }

    private static final class BreakableResponse extends HttpServletResponseWrapper {
        private final HttpServletResponse real;
        private final AtomicBoolean broken;

        BreakableResponse(HttpServletResponse real, AtomicBoolean broken) {
            super(real);
            this.real = real;
            this.broken = broken;
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            ServletOutputStream delegate = real.getOutputStream();
            return new ServletOutputStream() {
                @Override
                public void write(int value) throws IOException {
                    failIfBroken();
                    delegate.write(value);
                }

                @Override
                public void flush() throws IOException {
                    failIfBroken();
                    delegate.flush();
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setWriteListener(WriteListener listener) {
                }
            };
        }

        @Override
        public void flushBuffer() throws IOException {
            failIfBroken();
            real.flushBuffer();
        }

        private void failIfBroken() throws IOException {
            if (broken.get()) {
                throw new IOException("Broken pipe");
            }
        }
    }
}
