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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private static final String STREAM = "/api/v1/routes/123456789/watch/events";
    private static final String STREAM_HEADER = "X-Stream";
    private static final String FIRST_FRAME = "retry:\\d+\\n: connected\\n\\n";
    private static final String PING = "event:ping\ndata:1\n\n";

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
        assertThat(connections()).isZero();
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

    private void createRegistry(int maxConnections) {
        registry = new WatchStreamRegistry(
            new WatchStreamSettings(maxConnections, Duration.ofHours(1), Duration.ofMinutes(30)), meters);
        mvc = MockMvcBuilders.standaloneSetup(new WatchStreamController(registry)).addFilters(breakable).build();
    }

    private MvcResult open(String stream) throws Exception {
        return mvc.perform(get(STREAM).header(STREAM_HEADER, stream)).andReturn();
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
