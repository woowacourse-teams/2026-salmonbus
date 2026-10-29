package com.gustler.backend.api.favorite.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class FavoriteQueryConfigurationTest {
    @Test
    void 전용_실행기가_기본_비동기_실행기를_대체하지_않는다() {
        try (var context = new AnnotationConfigApplicationContext(
            FavoriteQueryConfiguration.class, TaskExecutionAutoConfiguration.class)) {
            assertThat(context.containsBean("applicationTaskExecutor")).isTrue();
            assertThat(context.getBean("favoriteArrivalExecutor"))
                .isNotSameAs(context.getBean("applicationTaskExecutor"));
        }
    }

    @Test
    void 네_작업이_실행_중이면_대기열에_쌓지_않고_종료할_때_실행기를_닫는다() throws Exception {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
            FavoriteQueryConfiguration.class);
        ExecutorService executor = context.getBean("favoriteArrivalExecutor", ExecutorService.class);
        CountDownLatch started = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        try {
            for (int index = 0; index < 4; index++) {
                executor.execute(() -> {
                    started.countDown();
                    try {
                        release.await(3, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> executor.execute(() -> { }))
                .isInstanceOf(RejectedExecutionException.class);
        } finally {
            release.countDown();
            context.close();
        }
        assertThat(executor.isTerminated()).isTrue();
    }
}
