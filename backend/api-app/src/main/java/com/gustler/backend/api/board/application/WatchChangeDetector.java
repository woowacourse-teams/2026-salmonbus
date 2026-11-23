package com.gustler.backend.api.board.application;

import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.route.RouteId;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.TransactionException;

/**
 * 화면이 보고 있는 노선의 판정보드를 주기적으로 읽어, 그 관측 시각을 {@link WatchSubscriptions} 에 알린다.
 *
 * <p>연결을 얻지 못하면 연결 풀의 대기 시간(기본 30초)만큼 기다리는데, 그동안 쥐는 연결은 없고 다음 확인도 시작하지 않는다.
 */
public final class WatchChangeDetector implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(WatchChangeDetector.class);

    private static final Duration[] LATENCY_BUCKETS = {
        Duration.ofMillis(25), Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(250),
        Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(2)
    };

    private final WatchSubscriptions subscriptions;
    private final BoardQueryService boards;
    private final Duration interval;
    private final Duration readTimeout;
    private final Timer reads;
    private final Timer failures;
    /** {@code TaskScheduler} 빈으로 등록하면 Spring 이 모든 {@code @Scheduled} 작업을 이 스레드로 옮기므로 빈이 아닌 필드다. */
    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    private boolean failing;

    /**
     * @param interval 확인 하나가 끝난 뒤 다음 확인까지 쉬는 시간
     * @param readTimeout 조회 문장 하나가 잠금 대기를 포함해 쓸 수 있는 시간
     */
    public WatchChangeDetector(WatchSubscriptions subscriptions, BoardQueryService boards, Duration interval,
        Duration readTimeout, MeterRegistry meters) {
        this.subscriptions = subscriptions;
        this.boards = boards;
        this.interval = interval;
        this.readTimeout = readTimeout;
        this.reads = checks(meters, "read");
        this.failures = checks(meters, "failed");
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("watch-change-");
        // 멈출 때 돌고 있는 확인에 인터럽트를 걸지 않는다. 살아 있는 연결에 신호를 쓰던 중일 수 있고, 인터럽트로는 DB 읽기가 끊기지도 않는다.
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
    }

    private static Timer checks(MeterRegistry meters, String result) {
        return Timer.builder("salmonbus.watch.stream.check")
            .description("변경 감지기의 확인 하나에 걸린 시간. 보는 노선이 없어 DB 를 읽지 않은 확인은 세지 않는다. "
                + "result 는 읽었으면 read, 읽기가 통째로 실패했으면 failed 다")
            .tag("result", result)
            .serviceLevelObjectives(LATENCY_BUCKETS)
            .register(meters);
    }

    public void start() {
        scheduler.initialize();
        scheduler.scheduleWithFixedDelay(this::check, interval);
    }

    void check() {
        final long startedAt = System.nanoTime();
        int watched = 0;
        try {
            Set<RouteId> routes = subscriptions.watchedRoutes();
            if (routes.isEmpty()) {
                return;
            }
            watched = routes.size();
            List<String> routeIds = routes.stream().map(RouteId::value).toList();
            Map<String, BoardBatchRead> boardReads = boards.readBoardsWithStatementLimit(routeIds, readTimeout);
            for (RouteId route : routes) {
                BoardReading reading = boardReads.get(route.value()).reading();
                if (reading != null) {
                    subscriptions.observed(route, reading.board().observedAt());
                }
            }
            this.reads.record(Duration.ofNanos(System.nanoTime() - startedAt));
            if (failing) {
                failing = false;
                LOGGER.info("event=watch_change_check status=RECOVERED routes={}", watched);
            }
        } catch (RuntimeException failure) {
            failures.record(Duration.ofNanos(System.nanoTime() - startedAt));
            logFailure(watched, failure);
            failing = true;
        }
    }

    private void logFailure(int watched, RuntimeException failure) {
        if (failing) {
            LOGGER.debug("event=watch_change_check status=FAILED routes={} cause={}",
                watched, failure.getClass().getSimpleName());
        } else if (failure instanceof ApiException || failure instanceof TransactionException) {
            LOGGER.warn("event=watch_change_check status=FAILED routes={} cause={}",
                watched, failure.getClass().getSimpleName());
        } else {
            LOGGER.warn("event=watch_change_check status=FAILED routes={}", watched, failure);
        }
    }

    @Override
    public void close() {
        scheduler.shutdown();
    }
}
