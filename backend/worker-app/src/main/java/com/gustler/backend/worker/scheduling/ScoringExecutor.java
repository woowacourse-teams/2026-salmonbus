package com.gustler.backend.worker.scheduling;

import com.gustler.backend.forecasting.api.pipeline.ScoringFeed;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

/**
 * 채점 줄이다. 전용 단일 스레드({@code forecast-scoring-})와 길이 제한이 있는 줄로 일을 넣은 순서대로 하나씩 실행한다.
 *
 * <p>그림자 실행 스레드와 주기 작업 스레드는 {@link #submit}에서 줄에 넣기만 하고 기다리지 않는다. 줄이 가득 차면 넣지 않고
 * 거짓을 돌려준다. 일 하나가 실패해도 스레드는 다음 일을 계속 실행한다. 줄 길이는 {@code salmonbus.pipeline.scoring.queue}
 * 게이지로 낸다. 종료할 때 기다리는 일은 버린다.
 */
public class ScoringExecutor implements ScoringFeed, DisposableBean {
    /** 채점 스레드 이름의 앞부분. */
    public static final String THREAD_PREFIX = "forecast-scoring-";
    private static final Logger LOGGER = LoggerFactory.getLogger(ScoringExecutor.class);

    private final ThreadPoolExecutor executor;

    /**
     * @param capacity 줄에 기다릴 수 있는 일의 수
     * @param meters 지표 등록기. 없으면 줄 길이 지표를 내지 않는다
     */
    public ScoringExecutor(final int capacity, MeterRegistry meters) {
        AtomicInteger sequence = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(capacity),
            runnable -> {
                Thread thread = new Thread(runnable, THREAD_PREFIX + sequence.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
        if (meters != null) {
            Gauge.builder("salmonbus.pipeline.scoring.queue", executor, pool -> pool.getQueue().size())
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
            LOGGER.warn("event=pipeline_scoring_task_failed exceptionType={}", failure.getClass().getSimpleName(),
                failure);
        }
    }

    /** 지금 줄에서 기다리는 일의 수. */
    public int queued() {
        return executor.getQueue().size();
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
