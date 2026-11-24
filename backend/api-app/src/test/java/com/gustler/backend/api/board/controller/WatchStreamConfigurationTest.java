package com.gustler.backend.api.board.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

import com.gustler.backend.api.board.application.BoardQueryService;
import com.gustler.backend.api.board.application.WatchChangeDetector;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;

class WatchStreamConfigurationTest {
    private static final Duration THREAD_STOP_TIMEOUT = Duration.ofSeconds(2);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(WatchStreamConfiguration.class, WatchStreamController.class)
        .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
        .withBean(BoardQueryService.class, () -> mock(BoardQueryService.class));

    @Test
    void 기본값이면_컨트롤러도_등록부도_변경_감지기도_스레드도_없다() {
        // given
        Set<Thread> before = streamThreads();

        // when
        runner.run(context -> {
            // then
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(WatchStreamController.class);
            assertThat(context).doesNotHaveBean(WatchStreamRegistry.class);
            assertThat(context).doesNotHaveBean(WatchStreamSettings.class);
            assertThat(context).doesNotHaveBean(WatchChangeDetector.class);
            assertThat(startedSince(before)).isEmpty();
        });
    }

    @Test
    void false로_꺼도_없다() {
        // given
        Set<Thread> before = streamThreads();

        // when
        runner.withPropertyValues("watch.stream.enabled=false").run(context -> {
            // then
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(WatchStreamController.class);
            assertThat(context).doesNotHaveBean(WatchStreamRegistry.class);
            assertThat(context).doesNotHaveBean(WatchStreamSettings.class);
            assertThat(context).doesNotHaveBean(WatchChangeDetector.class);
            assertThat(startedSince(before)).isEmpty();
        });
    }

    @Test
    void 켜면_컨트롤러와_등록부와_변경_감지기가_함께_생기고_컨텍스트를_닫으면_두_스레드가_멈춘다() {
        // given
        Set<Thread> before = streamThreads();

        // when
        runner.withPropertyValues("watch.stream.enabled=true").run(context -> {
            // then
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(WatchStreamController.class);
            assertThat(context).hasSingleBean(WatchStreamRegistry.class);
            assertThat(context).hasSingleBean(WatchChangeDetector.class);
            assertThat(context.getBean(WatchStreamSettings.class))
                .isEqualTo(new WatchStreamSettings(200, Duration.ofSeconds(15), Duration.ofMinutes(30), Duration.ofSeconds(1),
                    Duration.ofSeconds(1)));
            assertThat(context).doesNotHaveBean(TaskScheduler.class);
            assertThat(startedSince(before)).extracting(Thread::getName)
                .containsExactlyInAnyOrder("watch-stream-1", "watch-change-1");
        });
        await().atMost(THREAD_STOP_TIMEOUT).until(() -> startedSince(before).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "watch.stream.max-connections=0",
        "watch.stream.heartbeat-interval=0s",
        "watch.stream.timeout=0s",
        "watch.stream.detect-interval=0s",
        "watch.stream.read-timeout=999us"
    })
    void 범위를_벗어난_값이면_키_이름을_알리며_뜨지_않는다(String invalid) {
        // given
        Set<Thread> before = streamThreads();
        String key = invalid.substring(0, invalid.indexOf('='));

        // when
        runner.withPropertyValues("watch.stream.enabled=true", invalid).run(context -> {
            // then
            assertThat(context).hasFailed();
            assertThat(causeMessages(context.getStartupFailure())).contains(key);
        });
        await().atMost(THREAD_STOP_TIMEOUT).until(() -> startedSince(before).isEmpty());
    }

    private static Set<Thread> streamThreads() {
        return Thread.getAllStackTraces().keySet().stream()
            .filter(thread -> isStreamThread(thread.getName()) && thread.isAlive())
            .collect(Collectors.toCollection(HashSet::new));
    }

    private static boolean isStreamThread(String name) {
        return name.startsWith("watch-stream-") || name.startsWith("watch-change-");
    }

    /** 다른 시험이 닫은 등록부의 스레드가 그사이에 끝날 수 있어서, 수를 세지 않고 새로 생긴 스레드만 본다. */
    private static Set<Thread> startedSince(Set<Thread> before) {
        Set<Thread> started = streamThreads();
        started.removeAll(before);
        return started;
    }

    private static String causeMessages(Throwable failure) {
        return Stream.iterate(failure, Objects::nonNull, Throwable::getCause)
            .map(Throwable::getMessage)
            .filter(Objects::nonNull)
            .collect(Collectors.joining("\n"));
    }
}
