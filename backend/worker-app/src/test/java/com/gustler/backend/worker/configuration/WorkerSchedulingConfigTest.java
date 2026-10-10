package com.gustler.backend.worker.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class WorkerSchedulingConfigTest {
    @Test
    void 이관이_외부_응답을_기다려도_수집과_실시간_처리는_실행되고_통계는_겹치지_않는다() throws Exception {
        // given
        var config = new WorkerSchedulingConfig();
        var processing = config.processingScheduler();
        var collection = config.collectionScheduler();
        var calibration = config.calibrationScheduler();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        processing.initialize();
        collection.initialize();
        calibration.initialize();
        try {
            calibration.execute(() -> {
                started.countDown();
                try { release.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            });
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

            // when
            var statistics = calibration.submit(() -> Thread.currentThread().getName());
            var processingThread = processing.submit(() -> Thread.currentThread().getName()).get(1, TimeUnit.SECONDS);
            var collectionThread = collection.submit(() -> Thread.currentThread().getName()).get(1, TimeUnit.SECONDS);

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
        var statistics = com.gustler.backend.worker.scheduling.StopDemandPipelineJob.class.getMethod("advance");
        var archive = com.gustler.backend.worker.scheduling.EvaluationArchiveJob.class.getMethod("advance");

        // when & then
        assertThat(statistics.getAnnotation(org.springframework.scheduling.annotation.Scheduled.class).scheduler())
            .isEqualTo("calibrationTaskScheduler");
        assertThat(archive.getAnnotation(org.springframework.scheduling.annotation.Scheduled.class).scheduler())
            .isEqualTo("calibrationTaskScheduler");
    }

    @Test
    void 처리가_스레드를_사용하는_중에도_수집을_실행할_수_있다() throws Exception {
        var config=new WorkerSchedulingConfig();
        var processing=config.processingScheduler();
        var collection=config.collectionScheduler();
        var started=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        processing.initialize();
        collection.initialize();
        try {
            processing.execute(()->{
                started.countDown();
                try { release.await(3,TimeUnit.SECONDS); }
                catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertThat(started.await(2,TimeUnit.SECONDS)).isTrue();
            assertThat(collection.submit(()->Thread.currentThread().getName()).get(1,TimeUnit.SECONDS))
                .startsWith("collection-");
        } finally {
            release.countDown();
            collection.shutdown();
            processing.shutdown();
        }
    }
}
