package com.gustler.backend.api.board.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gustler.backend.api.board.NoRecentObservationException;
import com.gustler.backend.api.board.domain.Board;
import com.gustler.backend.api.board.domain.BoardRoute;
import com.gustler.backend.api.board.domain.ForecastModel;
import com.gustler.backend.api.board.domain.RouteHeadways;
import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.route.RouteNotFoundException;
import com.gustler.backend.api.route.domain.RouteStatus;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.assertj.core.api.AutoCloseableSoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.CannotCreateTransactionException;

class WatchChangeDetectorTest {
    private static final Duration READ_TIMEOUT = Duration.ofMillis(700);
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final OffsetDateTime X = OffsetDateTime.parse("2026-08-27T11:59:00+09:00");
    private static final RouteId READ = new RouteId("100000001");
    private static final RouteId STALE = new RouteId("100000002");
    private static final RouteId SKIPPED = new RouteId("100000003");
    private static final RouteId UNKNOWN = new RouteId("100000004");

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final RecordingSubscriptions subscriptions = new RecordingSubscriptions();
    private final ScriptedBoards boards = new ScriptedBoards();
    private final Logger detectorLogger = (Logger) LoggerFactory.getLogger(WatchChangeDetector.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Level loggerLevel = detectorLogger.getLevel();
    private WatchChangeDetector detector;

    @AfterEach
    void tearDown() {
        detectorLogger.detachAppender(logs);
        detectorLogger.setLevel(loggerLevel);
        CountDownLatch gate = boards.gate;
        if (gate != null) {
            gate.countDown();
        }
        if (detector != null) {
            detector.close();
        }
    }

    @Test
    void 보는_노선이_없으면_판정보드를_읽지_않는다() {
        // given
        detector = detector(Duration.ofSeconds(1));

        // when
        detector.check();

        // then
        assertThat(boards.calls).hasValue(0);
        assertThat(checks("read")).isZero();
        assertThat(checks("failed")).isZero();
    }

    @Test
    void 읽은_노선의_관측만_알리고_실패했거나_건너뛴_노선은_알리지_않는다() {
        // given
        detector = detector(Duration.ofSeconds(1));
        subscriptions.routes = Set.of(READ, STALE, SKIPPED, UNKNOWN);
        boards.answers.put(READ.value(), BoardBatchRead.read(reading(READ, X)));
        boards.answers.put(STALE.value(), BoardBatchRead.failed(new NoRecentObservationException()));
        boards.answers.put(SKIPPED.value(), BoardBatchRead.skippedByBudget());

        // when
        detector.check();

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(boards.calls).hasValue(1);
            softly.assertThat(boards.requested.getFirst())
                .containsExactlyInAnyOrder(READ.value(), STALE.value(), SKIPPED.value(), UNKNOWN.value());
            softly.assertThat(boards.limits).containsExactly(READ_TIMEOUT);
            softly.assertThat(subscriptions.told).containsExactly(READ.value() + "=" + X);
            softly.assertThat(checks("read")).isEqualTo(1);
            softly.assertThat(checks("failed")).isZero();
        }
    }

    @Test
    void 읽기가_통째로_실패해도_예외를_올리지_않고_다음_확인이_다시_읽는다() {
        // given
        detector = detector(Duration.ofSeconds(1));
        subscriptions.routes = Set.of(READ);
        boards.answers.put(READ.value(), BoardBatchRead.read(reading(READ, X)));
        boards.thrown = new CannotCreateTransactionException("연결 풀 대기 시간 초과");

        // when
        assertThatCode(detector::check).doesNotThrowAnyException();
        List<String> toldWhileFailing = List.copyOf(subscriptions.told);
        boards.thrown = null;
        detector.check();

        // then
        assertThat(toldWhileFailing).isEmpty();
        assertThat(subscriptions.told).containsExactly(READ.value() + "=" + X);
        assertThat(checks("failed")).isEqualTo(1);
        assertThat(checks("read")).isEqualTo(1);
    }

    @Test
    void 실패가_이어지면_처음만_경고하고_보는_노선이_없는_확인을_건너_회복하면_한_번_알린다() {
        // given
        detector = detector(Duration.ofSeconds(1));
        captureDetectorLogs();
        boards.answers.put(READ.value(), BoardBatchRead.read(reading(READ, X)));
        boards.thrown = new CannotCreateTransactionException("연결 풀 대기 시간 초과");

        // when
        subscriptions.routes = Set.of(READ);
        detector.check();
        subscriptions.routes = Set.of();
        detector.check();
        subscriptions.routes = Set.of(READ);
        detector.check();
        boards.thrown = null;
        detector.check();
        detector.check();

        // then
        assertThat(logs.list)
            .extracting(event -> event.getLevel() + " " + event.getFormattedMessage())
            .containsExactly(
                "WARN event=watch_change_check status=FAILED routes=1 cause=CannotCreateTransactionException",
                "DEBUG event=watch_change_check status=FAILED routes=1 cause=CannotCreateTransactionException",
                "INFO event=watch_change_check status=RECOVERED routes=1");
        assertThat(checks("failed")).isEqualTo(2);
        assertThat(checks("read")).isEqualTo(2);
    }

    @Test
    void 시작하면_전용_스레드에서_앞_확인이_끝난_뒤_간격을_두고_되풀이하고_닫으면_멈춘다() throws Exception {
        // given
        Duration interval = Duration.ofMillis(20);
        detector = detector(interval);
        subscriptions.routes = Set.of(READ);
        boards.answers.put(READ.value(), BoardBatchRead.read(reading(READ, X)));
        boards.busy = Duration.ofMillis(30);
        Set<Thread> before = detectorThreads();

        // when
        detector.start();
        await().atMost(WAIT).until(() -> boards.spans.size() >= 4);
        boards.entered = new CountDownLatch(1);
        boards.gate = new CountDownLatch(1);
        assertThat(boards.entered.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        CompletableFuture.runAsync(detector::close).get(WAIT.toSeconds(), TimeUnit.SECONDS);
        int callsAtClose = boards.calls.get();
        boards.gate.countDown();
        await().atMost(WAIT).until(() -> startedSince(before).isEmpty());

        // then
        List<long[]> spans = List.copyOf(boards.spans);
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(Set.copyOf(boards.threads)).containsExactly("watch-change-1");
            for (int index = 1; index < spans.size(); index++) {
                softly.assertThat(spans.get(index)[0] - spans.get(index - 1)[1])
                    .isGreaterThanOrEqualTo(interval.toNanos());
            }
            softly.assertThat(boards.interrupted).containsExactly(false);
            softly.assertThat(boards.calls.get()).isEqualTo(callsAtClose);
        }
    }

    private WatchChangeDetector detector(Duration interval) {
        return new WatchChangeDetector(subscriptions, boards, interval, READ_TIMEOUT, meters);
    }

    private void captureDetectorLogs() {
        detectorLogger.setLevel(Level.DEBUG);
        logs.start();
        detectorLogger.addAppender(logs);
    }

    private long checks(String result) {
        Timer timer = meters.find("salmonbus.watch.stream.check").tag("result", result).timer();
        return timer == null ? 0 : timer.count();
    }

    private static Set<Thread> detectorThreads() {
        return Thread.getAllStackTraces().keySet().stream()
            .filter(thread -> thread.getName().startsWith("watch-change-") && thread.isAlive())
            .collect(Collectors.toCollection(HashSet::new));
    }

    private static Set<Thread> startedSince(Set<Thread> before) {
        Set<Thread> started = detectorThreads();
        started.removeAll(before);
        return started;
    }

    private static BoardReading reading(RouteId route, OffsetDateTime observedAt) {
        Board board = new Board(
            new BoardRoute(route.value(), "노선", "기점", "종점", RouteStatus.PREPARING, null, List.of(), "v1",
                RouteHeadways.none()),
            observedAt, observedAt.plusMinutes(5), new ForecastModel(1L, "model", observedAt.minusDays(1)), 0, List.of());
        return new BoardReading(board, 1L, Duration.ofSeconds(15));
    }

    private static final class RecordingSubscriptions implements WatchSubscriptions {
        private volatile Set<RouteId> routes = Set.of();
        private final List<String> told = new CopyOnWriteArrayList<>();

        @Override
        public Set<RouteId> watchedRoutes() {
            return routes;
        }

        @Override
        public void observed(RouteId routeId, OffsetDateTime observedAt) {
            told.add(routeId.value() + "=" + observedAt);
        }
    }

    private static final class ScriptedBoards extends BoardQueryService {
        private final Map<String, BoardBatchRead> answers = new ConcurrentHashMap<>();
        private final AtomicInteger calls = new AtomicInteger();
        private final List<Duration> limits = new CopyOnWriteArrayList<>();
        private final List<List<String>> requested = new CopyOnWriteArrayList<>();
        private final List<String> threads = new CopyOnWriteArrayList<>();
        private final List<long[]> spans = new CopyOnWriteArrayList<>();
        private final List<Boolean> interrupted = new CopyOnWriteArrayList<>();
        private volatile RuntimeException thrown;
        private volatile Duration busy = Duration.ZERO;
        private volatile CountDownLatch entered;
        private volatile CountDownLatch gate;

        ScriptedBoards() {
            super(null, null, null, null);
        }

        @Override
        public Map<String, BoardBatchRead> readBoardsWithStatementLimit(
            Collection<String> routeIds, Duration statementLimit) {
            final long startedAt = System.nanoTime();
            calls.incrementAndGet();
            limits.add(statementLimit);
            requested.add(List.copyOf(routeIds));
            threads.add(Thread.currentThread().getName());
            holdIfGated();
            try {
                Thread.sleep(busy);
            } catch (InterruptedException interrupt) {
                Thread.currentThread().interrupt();
            }
            RuntimeException failure = thrown;
            if (failure != null) {
                throw failure;
            }
            Map<String, BoardBatchRead> reads = new LinkedHashMap<>();
            for (String routeId : routeIds) {
                reads.put(routeId, answers.getOrDefault(routeId,
                    BoardBatchRead.failed(new RouteNotFoundException())));
            }
            spans.add(new long[] {startedAt, System.nanoTime()});
            return reads;
        }

        private void holdIfGated() {
            CountDownLatch enteredNow = entered;
            CountDownLatch gateNow = gate;
            if (enteredNow == null || gateNow == null) {
                return;
            }
            enteredNow.countDown();
            boolean wasInterrupted = false;
            while (true) {
                try {
                    gateNow.await();
                    break;
                } catch (InterruptedException interrupt) {
                    wasInterrupted = true;
                }
            }
            interrupted.add(wasInterrupted);
        }
    }
}
