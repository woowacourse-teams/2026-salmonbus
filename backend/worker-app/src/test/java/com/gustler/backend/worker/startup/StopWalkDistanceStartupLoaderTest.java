package com.gustler.backend.worker.startup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gustler.backend.routecatalog.api.LoadStopWalkDistances;
import com.gustler.backend.routecatalog.api.LoadStopWalkDistances.Result;
import com.gustler.backend.routecatalog.api.LoadStopWalkDistances.Status;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class StopWalkDistanceStartupLoaderTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-06T06:00:00Z"), ZoneOffset.UTC);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(StopWalkDistanceStartupLoader.class);
    private final List<List<Object>> requested = new ArrayList<>();
    private final LoadStopWalkDistances recording = (distances, metadata, loadedAt) -> {
        requested.add(List.of(distances, metadata, loadedAt));
        return new Result(Status.LOADED, "a".repeat(64), 2);
    };

    @TempDir
    Path directory;

    @BeforeEach
    void 로그_수집을_시작한다() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void 로그_수집을_종료한다() {
        logger.detachAppender(logs);
        logs.stop();
    }

    @Test
    void 디렉터리의_두_파일을_기동_시각으로_적재한다() throws IOException {
        // given
        placeFiles();

        // when
        loaderWith(recording, directory.toString()).loadPlacedDistances();

        // then
        assertThat(requested).containsExactly(List.of(directory.resolve("stop-walk-distances.csv.gz"),
            directory.resolve("stop-walk-distances.json"), OffsetDateTime.now(CLOCK)));
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
            "event=stop_walk_distances_loaded result=LOADED checksum=" + "a".repeat(64) + " pairCount=2");
    }

    @Test
    void 디렉터리를_비워_두면_건너뛴다() {
        // when
        loaderWith(recording, "").loadPlacedDistances();

        // then
        assertThat(requested).isEmpty();
        assertThat(logs.list).isEmpty();
    }

    @Test
    void 파일이_없으면_경고만_남기고_건너뛴다() {
        // when
        loaderWith(recording, directory.toString()).loadPlacedDistances();

        // then
        assertThat(requested).isEmpty();
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).startsWith("event=stop_walk_distances_missing");
        });
    }

    @Test
    void 적재가_실패해도_기동을_막지_않고_오류로_남긴다() throws IOException {
        // given
        placeFiles();
        LoadStopWalkDistances broken = (distances, metadata, loadedAt) -> {
            throw new IllegalStateException("broken file");
        };

        // when, then
        assertThatCode(() -> loaderWith(broken, directory.toString()).loadPlacedDistances())
            .doesNotThrowAnyException();
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage())
                .isEqualTo("event=stop_walk_distances_load_failed exceptionType=IllegalStateException");
        });
    }

    @Test
    void 노선_카탈로그_구성이_없는_실행에서는_건너뛴다() throws IOException {
        // given
        placeFiles();

        // when
        new StopWalkDistanceStartupLoader(new StaticListableBeanFactory().getBeanProvider(LoadStopWalkDistances.class),
            CLOCK, directory.toString()).loadPlacedDistances();

        // then
        assertThat(logs.list).isEmpty();
    }

    private void placeFiles() throws IOException {
        Files.writeString(directory.resolve("stop-walk-distances.csv.gz"), "");
        Files.writeString(directory.resolve("stop-walk-distances.json"), "{}");
    }

    private static StopWalkDistanceStartupLoader loaderWith(LoadStopWalkDistances load, String directory) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("loadStopWalkDistances", load);
        return new StopWalkDistanceStartupLoader(beans.getBeanProvider(LoadStopWalkDistances.class), CLOCK, directory);
    }
}
