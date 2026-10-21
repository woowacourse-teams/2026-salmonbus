package com.gustler.backend.worker.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.worker.scheduling.EvaluationArchiveJob;
import com.gustler.backend.worker.scheduling.StopDemandPipelineJob;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.AutoCloseableSoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class WorkerSchedulingConfigTest {
    @Test
    void 이관이_외부_응답을_기다려도_수집과_실시간_처리는_실행되고_통계는_겹치지_않는다() throws Exception {
        // given
        WorkerSchedulingConfig config = new WorkerSchedulingConfig();
        ThreadPoolTaskScheduler processing = config.processingScheduler();
        ThreadPoolTaskScheduler collection = config.collectionScheduler();
        ThreadPoolTaskScheduler calibration = config.calibrationScheduler();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        processing.initialize();
        collection.initialize();
        calibration.initialize();
        try {
            calibration.execute(() -> {
                started.countDown();
                try {
                    release.await(3, TimeUnit.SECONDS);
                }
                catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

            // when
            Future<String> statistics = calibration.submit(() -> Thread.currentThread().getName());
            String processingThread = processing.submit(() -> Thread.currentThread()
                .getName())
                .get(1, TimeUnit.SECONDS);
            String collectionThread = collection.submit(() -> Thread.currentThread()
                .getName())
                .get(1, TimeUnit.SECONDS);

            // then
            assertThat(processingThread).startsWith("processing-");
            assertThat(collectionThread).startsWith("collection-");
            assertThat(statistics.isDone()).isFalse();
            release.countDown();
            assertThat(statistics.get(1, TimeUnit.SECONDS)).startsWith("calibration-");
            assertThat(calibration.getPoolSize()).isOne();
        } finally {
            release.countDown();
            calibration.shutdown();
            collection.shutdown();
            processing.shutdown();
        }
    }

    @Test
    void 통계와_이관_스케줄은_같은_전용_실행기를_지정한다() throws Exception {
        // given
        Method statistics = StopDemandPipelineJob.class.getMethod("advance");
        Method archive = EvaluationArchiveJob.class.getMethod("advance");

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(statistics.getAnnotation(Scheduled.class).scheduler())
                .isEqualTo("calibrationTaskScheduler");
            softly.assertThat(archive.getAnnotation(Scheduled.class).scheduler())
                .isEqualTo("calibrationTaskScheduler");
        }
    }

    @Test
    void 처리가_스레드를_사용하는_중에도_수집을_실행할_수_있다() throws Exception {
        // when
        WorkerSchedulingConfig config = new WorkerSchedulingConfig();
        ThreadPoolTaskScheduler processing = config.processingScheduler();
        ThreadPoolTaskScheduler collection = config.collectionScheduler();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        processing.initialize();
        collection.initialize();
        try {
            processing.execute(()->{
                started.countDown();
                try {
                    release.await(3, TimeUnit.SECONDS);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            // then
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(collection.submit(()->Thread.currentThread().getName()).get(1, TimeUnit.SECONDS))
                .startsWith("collection-");
        } finally {
            release.countDown();
            collection.shutdown();
            processing.shutdown();
        }
    }

    @Test
    void 처리가_스레드를_사용하는_중에도_예보를_전용_스레드에서_실행한다() throws Exception {
        // given
        WorkerSchedulingConfig config = new WorkerSchedulingConfig();
        ThreadPoolTaskScheduler processing = config.processingScheduler();
        ThreadPoolTaskScheduler forecast = config.forecastScheduler();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        processing.initialize();
        forecast.initialize();
        try {
            processing.execute(() -> {
                started.countDown();
                try {
                    release.await(3, TimeUnit.SECONDS);
                }
                catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

            // when
            String forecastThread = forecast.submit(() -> Thread.currentThread().getName()).get(1, TimeUnit.SECONDS);

            // then
            assertThat(forecastThread).startsWith("forecast-");
            assertThat(forecast.getPoolSize()).isOne();
        } finally {
            release.countDown();
            forecast.shutdown();
            processing.shutdown();
        }
    }
}
