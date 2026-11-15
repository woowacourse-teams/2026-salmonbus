package com.gustler.backend.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

class LegacyExportSettingsTest {
    static final String PREFIX = "salmonbus/prod/lake-v3-legacy/";
    final JdbcClient jdbc = mock(JdbcClient.class);
    final ArchiveObjects objects = mock(ArchiveObjects.class);
    final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withInitializer(context -> context.getBeanFactory()
            .setConversionService(ApplicationConversionService.getSharedInstance()))
        .withUserConfiguration(LegacyExportConfiguration.class)
        .withBean(JdbcClient.class, () -> jdbc)
        .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
        .withBean(ArchiveObjects.class, () -> objects)
        .withBean(Clock.class, Clock::systemUTC)
        .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
        .withPropertyValues("archive.prefix=salmonbus/prod/lake-v2/");

    static LegacyExportSettings settings(
        String prefix,
        Duration interval,
        final int pageSize,
        Duration pause,
        final long segmentBytes,
        final long maxRows,
        Duration maxRun,
        Duration statementTimeout
    ) {
        return new LegacyExportSettings(prefix, interval, pageSize, pause, segmentBytes, maxRows, maxRun,
            statementTimeout);
    }

    @Test
    void 범위를_벗어난_설정값은_받지_않는다() {
        // given
        Duration minute = Duration.ofMinutes(1);
        Duration pause = Duration.ofMillis(200);
        final long segment = 64L * 1024 * 1024;
        Duration run = Duration.ofMinutes(10);
        Duration statement = Duration.ofSeconds(60);

        // then
        for (Runnable invalid : List.<Runnable>of(
            () -> settings("salmonbus/prod/lake-v3-legacy", minute, 2000, pause, segment, 1000, run, statement),
            () -> settings("/salmonbus/legacy/", minute, 2000, pause, segment, 1000, run, statement),
            () -> settings("salmonbus-be/legacy/", minute, 2000, pause, segment, 1000, run, statement),
            () -> settings(PREFIX, Duration.ofSeconds(9), 2000, pause, segment, 1000, run, statement),
            () -> settings(PREFIX, Duration.ofHours(25), 2000, pause, segment, 1000, run, statement),
            () -> settings(PREFIX, minute, 0, pause, segment, 1000, run, statement),
            () -> settings(PREFIX, minute, 10001, pause, segment, 1000, run, statement),
            () -> settings(PREFIX, minute, 2000, Duration.ofMillis(-1), segment, 1000, run, statement),
            () -> settings(PREFIX, minute, 2000, Duration.ofSeconds(61), segment, 1000, run, statement),
            () -> settings(PREFIX, minute, 2000, pause, 64 * 1024 - 1, 1000, run, statement),
            () -> settings(PREFIX, minute, 2000, pause, 1024L * 1024 * 1024 + 1, 1000, run, statement),
            () -> settings(PREFIX, minute, 2000, pause, segment, 0, run, statement),
            () -> settings(PREFIX, minute, 2000, pause, segment, 1_000_000_001L, run, statement),
            () -> settings(PREFIX, minute, 2000, pause, segment, 1000, Duration.ofMillis(999), statement),
            () -> settings(PREFIX, minute, 2000, pause, segment, 1000, Duration.ofHours(7), statement),
            () -> settings(PREFIX, minute, 2000, pause, segment, 1000, run, Duration.ofMillis(999)),
            () -> settings(PREFIX, minute, 2000, pause, segment, 1000, run, Duration.ofMinutes(11)))) {
            assertThatThrownBy(invalid::run).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void 다른_이관의_S3_접두와_겹치면_받지_않는다() {
        for (String prefix : List.of("salmonbus/prod/lake-v2/", "salmonbus/prod/lake-v3/legacy/", "salmonbus/")) {
            assertThatThrownBy(() -> new LegacyExportSettings(prefix, Duration.ofMinutes(1), 2000,
                Duration.ofMillis(200), 64L * 1024 * 1024, 1000, Duration.ofMinutes(10), Duration.ofSeconds(60),
                "salmonbus/prod/lake-v2/", "salmonbus/prod/lake-v3/")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void 스위치가_꺼져_있으면_내보내기_빈을_만들지_않고_DB를_보지_않는다() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(LegacyExportService.class);
            assertThat(context).doesNotHaveBean(LegacyExportRepository.class);
            assertThat(context).doesNotHaveBean(LegacyExportSettings.class);
        });
        runner.withPropertyValues("archive.legacy-export.enabled=false").run(context ->
            assertThat(context).doesNotHaveBean(LegacyExportService.class));
        verifyNoInteractions(jdbc, objects);
    }

    @Test
    void 스위치가_켜지면_기본값으로_빈을_만들고_줄_단위_이관이_켜져_있거나_값이_잘못되면_기동하지_않는다() {
        runner.withPropertyValues("archive.legacy-export.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(LegacyExportSettings.class)).isEqualTo(new LegacyExportSettings(PREFIX,
                Duration.ofMinutes(1), 2000, Duration.ofMillis(200), 64L * 1024 * 1024, 2_000_000,
                Duration.ofMinutes(10), Duration.ofSeconds(60)));
            assertThat(context).hasSingleBean(LegacyExportService.class);
        });
        runner.withPropertyValues("archive.legacy-export.enabled=true", "archive.enabled=true")
            .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("archive.legacy-export.enabled=true", "archive.legacy-export.page-size=0")
            .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("archive.legacy-export.enabled=true",
            "archive.legacy-export.prefix=salmonbus/prod/lake-v3/").run(context -> assertThat(context).hasFailed());
        verifyNoInteractions(jdbc, objects);
    }

    @Test
    void 내보내기_대상은_삭제_SQL과_사전_확인_SQL의_삭제_대상_테이블과_같은_목록_같은_순서다() throws IOException {
        String drop = Files.readString(sql("sal434-03-drop-legacy-tables.sql"));
        Matcher targets = Pattern.compile("target_tables constant text\\[\\] := ARRAY\\[(.*?)\\];", Pattern.DOTALL)
            .matcher(drop);
        assertThat(targets.find()).as("sal434-03의 target_tables").isTrue();
        List<String> dropTables = Pattern.compile("'([a-z_]+)'").matcher(targets.group(1)).results()
            .map(m -> m.group(1)).toList();

        String precheck = Files.readString(sql("sal434-01-precheck.sql"));
        Matcher listed = Pattern.compile("SET LOCAL sal434.precheck_targets = '([a-z_,]+)';").matcher(precheck);
        assertThat(listed.find()).as("sal434-01의 precheck_targets").isTrue();
        List<String> precheckTables = Arrays.stream(listed.group(1).split(",")).skip(9).toList();

        assertThat(LegacyExportRepository.TABLES).hasSize(25).doesNotHaveDuplicates()
            .containsExactlyElementsOf(dropTables).containsExactlyElementsOf(precheckTables);
    }

    private static Path sql(String file) {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.isRegularFile(directory.resolve("deploy/sql").resolve(file))) {
            directory = directory.getParent();
        }
        if (directory == null) {
            throw new IllegalStateException("deploy/sql 을 찾을 수 없다: " + file);
        }
        return directory.resolve("deploy/sql").resolve(file);
    }
}
