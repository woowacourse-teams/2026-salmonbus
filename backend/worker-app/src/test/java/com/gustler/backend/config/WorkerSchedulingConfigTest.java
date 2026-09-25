package com.gustler.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class WorkerSchedulingConfigTest {
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
