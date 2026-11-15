package com.gustler.backend.worker.scheduling;

import com.gustler.backend.forecasting.api.pipeline.ShadowForecastFeed;
import com.gustler.backend.forecasting.api.pipeline.ShadowForecastTask;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

/**
 * 그림자 실행 줄이다. 전용 단일 스레드({@code forecast-shadow-})와 길이 제한이 있는 줄로 일을 넣은 순서대로 하나씩 실행한다.
 *
 * <p>옛 경로의 예보 스레드는 {@link #submit}에서 줄에 넣기만 하고 기다리지 않는다. 줄이 가득 차면 새 일을 버리고
 * {@link ShadowForecastTask#discard()}로 그 노선의 상태를 버리게 한다. 일 하나가 실패해도 스레드는 다음 일을 계속 실행한다.
 * 줄 길이는 {@code salmonbus.pipeline.shadow.queue} 게이지로 낸다.
 *
 * <p>정상 종료 때는 {@link PipelineShutdown}이 {@link #drain}으로 받은 일을 끝까지 실행하게 한다. 그러지 못하고 빈이 파괴되면
 * 기다리는 일을 버린다.
 */
public class ShadowForecastExecutor implements ShadowForecastFeed, DrainableQueue, DisposableBean {
    /** 그림자 실행 스레드 이름의 앞부분. */
    public static final String THREAD_PREFIX = "forecast-shadow-";
    private static final Logger LOGGER = LoggerFactory.getLogger(ShadowForecastExecutor.class);

    private final ThreadPoolExecutor executor;

    /**
     * @param capacity 줄에 기다릴 수 있는 일의 수
     * @param meters 지표 등록기. 없으면 줄 길이 지표를 내지 않는다
     */
    public ShadowForecastExecutor(final int capacity, MeterRegistry meters) {
        AtomicInteger sequence = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(capacity),
            runnable -> {
                Thread thread = new Thread(runnable, THREAD_PREFIX + sequence.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
        if (meters != null) {
            Gauge.builder("salmonbus.pipeline.shadow.queue", executor, pool -> pool.getQueue().size())
                .strongReference(true).register(meters);
        }
    }

    @Override
    public void submit(ShadowForecastTask task) {
        try {
            executor.execute(() -> runSafely(task));
        } catch (RejectedExecutionException full) {
            discardSafely(task);
        }
    }

    private static void runSafely(ShadowForecastTask task) {
        try {
            task.run();
        } catch (RuntimeException failure) {
            LOGGER.warn("event=pipeline_shadow_task_failed route={} batchId={} exceptionType={}", task.route(),
                task.observationBatchId(), failure.getClass().getSimpleName(), failure);
        }
    }

    private static void discardSafely(ShadowForecastTask task) {
        try {
            task.discard();
        } catch (RuntimeException failure) {
            LOGGER.warn("event=pipeline_shadow_discard_failed route={} batchId={} exceptionType={}", task.route(),
                task.observationBatchId(), failure.getClass().getSimpleName());
        }
    }

    /** 지금 줄에서 기다리는 일의 수. */
    public int queued() {
        return executor.getQueue().size();
    }

    /** 닫은 뒤 {@link #submit}은 새 일을 버리고 {@link ShadowForecastTask#discard()}를 부른다. */
    @Override
    public QueueDrain drain(Duration timeout) {
        return QueueDrain.of(List.of(executor), timeout);
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
