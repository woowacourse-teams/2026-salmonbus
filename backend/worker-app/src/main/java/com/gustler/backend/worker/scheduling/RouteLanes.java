package com.gustler.backend.worker.scheduling;

import com.gustler.backend.forecasting.api.pipeline.ForecastLanes;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ExecutorConfigurationSupport;

/**
 * 예보의 노선별 실행 줄이다. 단일 스레드 실행기({@code forecast-lane-<번호>-})를
 * {@code forecast.lanes}개 두고, 노선 열쇠의 해시로 줄 하나에 고정 배정한다.
 *
 * <p>같은 열쇠의 일은 항상 같은 줄에서 넣은 순서대로 하나씩 실행되고, 다른 줄의 일은 동시에 실행된다. 스케줄러의 풀 크기를 올리는
 * 방식과 달리 같은 노선이 두 스레드에서 동시에 처리되지 않는다. 줄마다 길이 제한이 있고, 가득 차면 넣지 않고 거짓을 돌려준다
 * (다음 줍기가 다시 넣는다). 일 하나가 실패해도 그 줄은 다음 일을 계속 실행한다. 줄 길이는
 * {@code salmonbus.pipeline.forecast.queue{lane}} 게이지로 낸다.
 *
 * <p>컨텍스트가 닫힐 때는 일을 넣는 수집과 줍기의 스케줄러가 돌던 회차를 끝낸 바로 다음에 닫힌다({@link #stop()}). 그래서 돌던
 * 수집 회차가 넘긴 배치까지는 받는다. 닫을 때는 새 일을 받지 않고, 줄에서 기다리던 일은 버리고, 돌던 일은 중단시키지 않고 끝나기를
 * 한도까지 기다린다. 돌던 일은 {@link #closing()}을 보고 처리하던 배치 하나만 끝낸다. 스프링은 수명 주기 빈을 모두 멈춘 뒤에 빈을
 * 파괴하므로 돌던 일은 DB 연결 풀이 닫히기 전에 끝난다. 버린 일이 처리했을 배치는 다시 뜬 뒤 줍기가 찾는다. 한 번 닫으면 다시 열 수
 * 없어 컨텍스트 일시 정지에는 참여하지 않는다.
 */
public class RouteLanes implements ForecastLanes, SmartLifecycle {
    /** 실행 줄 스레드 이름의 앞부분. */
    public static final String THREAD_PREFIX = "forecast-lane-";
    /** worker가 쓰는 줄 하나의 길이. */
    public static final int QUEUE_CAPACITY = 1_000;
    /**
     * worker가 닫을 때 돌던 일이 끝나기를 기다리는 한도. 스프링이 스케줄러의 돌던 회차를 기다리는 시한(기본 30초)과 채점 줄의 한도를
     * 더해도 서비스 유닛의 강제 종료 시한(45초)보다 짧아야 한다.
     */
    public static final Duration STOP_WAIT = Duration.ofSeconds(5);
    /** 스프링의 스케줄러가 돌던 회차를 끝내는 단계의 바로 다음 단계다. 단계가 큰 것부터 멈춘다. */
    static final int PHASE = ExecutorConfigurationSupport.DEFAULT_PHASE - 1;
    private static final Logger LOGGER = LoggerFactory.getLogger(RouteLanes.class);

    private final List<ThreadPoolExecutor> lanes;
    private final Duration stopWait;

    /**
     * @param count 줄 수
     * @param capacity 줄 하나에 기다릴 수 있는 일의 수
     * @param stopWait 닫을 때 돌던 일이 끝나기를 기다리는 한도. 모든 줄에 한 번 적용한다
     * @param meters 지표 등록기. 없으면 줄 길이 지표를 내지 않는다
     */
    public RouteLanes(
        final int count,
        final int capacity,
        Duration stopWait,
        MeterRegistry meters
    ) {
        if (count < 1 || capacity < 1) {
            throw new IllegalArgumentException("줄 수와 줄 길이는 1 이상이어야 한다");
        }
        List<ThreadPoolExecutor> created = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            ThreadPoolExecutor executor = createLane(index, capacity);
            created.add(executor);
            if (meters != null) {
                registerQueueGauge(meters, executor, index);
            }
        }
        this.lanes = List.copyOf(created);
        this.stopWait = stopWait;
    }

    private static ThreadPoolExecutor createLane(final int index, final int capacity) {
        String prefix = THREAD_PREFIX + index + "-";
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(capacity), runnable -> {
                Thread thread = new Thread(runnable, prefix + "1");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    }

    private static void registerQueueGauge(MeterRegistry meters, ThreadPoolExecutor executor, final int index) {
        Gauge.builder("salmonbus.pipeline.forecast.queue", executor, pool -> pool.getQueue().size())
            .tag("lane", String.valueOf(index))
            .strongReference(true)
            .register(meters);
    }

    @Override
    public boolean submit(String routeKey, Runnable task) {
        try {
            lanes.get(laneOf(routeKey)).execute(() -> runSafely(routeKey, task));
            return true;
        } catch (RejectedExecutionException full) {
            return false;
        }
    }

    private static void runSafely(String routeKey, Runnable task) {
        try {
            task.run();
        } catch (RuntimeException failure) {
            LOGGER.warn("event=pipeline_forecast_task_failed routeKey={} exceptionType={}", routeKey,
                failure.getClass().getSimpleName(), failure);
        }
    }

    /** 닫기 시작한 뒤로 참이다. 줄을 닫는 스레드가 돌던 일을 기다리는 동안에도 참이다. */
    @Override
    public boolean closing() {
        return lanes.getFirst().isShutdown();
    }

    /** 노선 열쇠가 배정되는 줄 번호. */
    public int laneOf(String routeKey) {
        return Math.floorMod(routeKey.hashCode(), lanes.size());
    }

    /** 줄 수. */
    public int count() {
        return lanes.size();
    }

    /** 지금 모든 줄에서 기다리는 일의 수. */
    public int queued() {
        return lanes.stream().mapToInt(lane -> lane.getQueue().size()).sum();
    }

    /** 일하는 스레드는 첫 일이 들어올 때 만들어지므로 시작할 것이 없다. */
    @Override
    public void start() {
    }

    /**
     * 모든 줄을 닫는다. 닫은 뒤 {@link #submit}은 거짓을 돌려준다. 한도 안에 돌던 일이 끝나지 않으면 경고를 남기고 돌아온다. 그
     * 일은 DB 연결 풀이 닫힌 뒤 실패로 끝날 수 있으며, 트랜잭션이 통째로 되돌려지므로 반쯤 쓴 줄은 남지 않는다.
     */
    @Override
    public void stop() {
        int discarded = 0;
        for (ThreadPoolExecutor lane : lanes) {
            lane.shutdown();
            discarded += lane.getQueue().drainTo(new ArrayList<>());
        }
        final long deadline = System.nanoTime() + stopWait.toNanos();
        int running = 0;
        for (ThreadPoolExecutor lane : lanes) {
            if (!terminatesBy(lane, deadline)) {
                running++;
            }
        }
        if (running > 0) {
            LOGGER.warn("event=pipeline_forecast_stop_timeout discarded={} running={} waitMs={}", discarded, running,
                stopWait.toMillis());
            return;
        }
        LOGGER.info("event=pipeline_forecast_stopped discarded={}", discarded);
    }

    private static boolean terminatesBy(ThreadPoolExecutor lane, final long deadline) {
        try {
            return lane.awaitTermination(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return lane.isTerminated();
        }
    }

    /** 닫기 전에는 참이다. 스프링은 참일 때만 {@link #stop()}을 부른다. */
    @Override
    public boolean isRunning() {
        return !lanes.getFirst().isShutdown();
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
