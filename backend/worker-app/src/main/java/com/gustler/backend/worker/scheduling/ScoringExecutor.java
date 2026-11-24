package com.gustler.backend.worker.scheduling;

import com.gustler.backend.forecasting.api.forecast.ScoringFeed;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * 채점 줄이다. 전용 단일 스레드({@code forecast-scoring-})와 길이 제한이 있는 줄로 일을 넣은 순서대로 하나씩 실행한다.
 *
 * <p>예보 실행 줄의 스레드와 주기 작업 스레드는 {@link #submit}에서 줄에 넣기만 하고 기다리지 않는다. 줄이 가득 차면 넣지 않고
 * 거짓을 돌려준다. 일 하나가 실패해도 스레드는 다음 일을 계속 실행한다. 줄 길이는 {@code salmonbus.scoring.queue}
 * 게이지로 낸다.
 *
 * <p>컨텍스트가 닫히면 예보 실행 줄({@link RouteLanes}) 바로 다음에 닫힌다({@link #stop()}). 그래야 예보가 마지막으로 끝낸 배치의
 * 사건까지 받는다. 새 일을 받지 않고, 줄에서 기다리던 일은 버리고, 돌던 일에 중단을 요청한 뒤 끝나기를 한도까지 기다린다. 채점
 * 스레드는 DB를 읽기만 하므로 중단돼도 반쯤 쓴 줄이 없다. 버린 사건은 저장된 기록에서 나온 것이라 다시 뜬 뒤 채점 되감기가 다시
 * 만든다. 한 번 닫으면 다시 열 수 없어 컨텍스트 일시 정지에는 참여하지 않는다.
 */
public class ScoringExecutor implements ScoringFeed, SmartLifecycle {

    /** 채점 스레드 이름의 앞부분. */
    public static final String THREAD_PREFIX = "forecast-scoring-";
    /** worker가 쓰는 줄 길이. 넘치면 사건을 버리고 그 노선을 다시 되감는다. */
    public static final int QUEUE_CAPACITY = 10_000;
    /** worker가 닫을 때 돌던 일이 끝나기를 기다리는 한도. */
    public static final Duration STOP_WAIT = Duration.ofSeconds(5);
    /** 예보 실행 줄이 멈춘 다음 단계다. */
    private static final int PHASE = RouteLanes.PHASE - 1;
    private static final Logger LOGGER = LoggerFactory.getLogger(ScoringExecutor.class);

    private final ThreadPoolExecutor executor;
    private final Duration stopWait;

    /**
     * @param capacity 줄에 기다릴 수 있는 일의 수
     * @param stopWait 닫을 때 돌던 일이 끝나기를 기다리는 한도
     * @param meters 지표 등록기. 없으면 줄 길이 지표를 내지 않는다
     */
    public ScoringExecutor(final int capacity, Duration stopWait, MeterRegistry meters) {
        AtomicInteger sequence = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity),
                runnable -> {
                    Thread thread = new Thread(runnable, THREAD_PREFIX + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
        this.stopWait = stopWait;
        if (meters != null) {
            Gauge.builder("salmonbus.scoring.queue", executor, pool -> pool.getQueue().size())
                .strongReference(true).register(meters);
        }
    }

    @Override
    public boolean submit(Runnable task) {
        try {
            executor.execute(() -> runSafely(task));
            return true;
        } catch (RejectedExecutionException full) {
            return false;
        }
    }

    private static void runSafely(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException failure) {
            LOGGER.warn("event=scoring_task_failed exceptionType={}", failure.getClass().getSimpleName());
        }
    }

    /** 지금 줄에서 기다리는 일의 수. */
    public int queued() {
        return executor.getQueue().size();
    }

    /** 일하는 스레드는 첫 일이 들어올 때 만들어지므로 시작할 것이 없다. */
    @Override
    public void start() {
    }

    /** 줄을 닫는다. 닫은 뒤 {@link #submit}은 거짓을 돌려준다. 한도 안에 돌던 일이 끝나지 않으면 경고를 남기고 돌아온다. */
    @Override
    public void stop() {
        final int discarded = executor.shutdownNow().size();
        if (!terminates()) {
            LOGGER.warn("event=scoring_stop_timeout discarded={} waitMs={}", discarded, stopWait.toMillis());
            return;
        }
        LOGGER.info("event=scoring_stopped discarded={}", discarded);
    }

    private boolean terminates() {
        try {
            return executor.awaitTermination(stopWait.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return executor.isTerminated();
        }
    }

    /** 닫기 전에는 참이다. 스프링은 참일 때만 {@link #stop()}을 부른다. */
    @Override
    public boolean isRunning() {
        return !executor.isShutdown();
    }

    @Override
    public boolean isPauseable() {
        return false;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
