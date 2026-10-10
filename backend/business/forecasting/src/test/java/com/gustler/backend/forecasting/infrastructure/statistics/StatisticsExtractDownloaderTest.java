package com.gustler.backend.forecasting.infrastructure.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.forecasting.domain.evaluation.ScoringState;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsVersion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class StatisticsExtractDownloaderTest {
    @TempDir Path directory;

    @Test
    void 다시_받은_자료는_원본과_같고_독립된_디렉터리에서_읽는다() throws IOException {
        // given
        var source = bundle();
        var commands = new ArrayList<List<String>>();
        var downloader = new StatisticsExtractDownloader(command -> {
            commands.add(command);
            copy(source, command);
        });

        // when
        var downloaded = downloader.download(directory, UUID.randomUUID(), source.manifest());

        // then
        assertThat(downloaded.directory()).isNotEqualTo(source.directory());
        assertThat(Files.readAllBytes(downloaded.directory().resolve("rows.jsonl")))
            .isEqualTo(Files.readAllBytes(source.directory().resolve("rows.jsonl")));
        assertThat(commands).hasSize(2);
        assertThat(commands.getFirst()).contains("--range", "bytes=0-1048576");
        assertThat(commands.getLast()).contains("--range", "bytes=0-" + source.manifest().files().getFirst().byteCount());
    }

    @Test
    void 파일_목록이_다르면_자료를_받기_전에_중단한다() {
        // given
        var source = bundle();
        var commands = new ArrayList<List<String>>();
        var downloader = new StatisticsExtractDownloader(command -> {
            commands.add(command);
            write(Path.of(command.getLast()), "{}");
        });

        // when & then
        assertThatThrownBy(() -> downloader.download(directory, UUID.randomUUID(), source.manifest()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("파일 목록");
        assertThat(commands).hasSize(1);
    }

    @Test
    void 파일_내용이_달라지면_검증된_묶음을_반환하지_않는다() {
        // given
        var source = bundle();
        var downloader = new StatisticsExtractDownloader(command -> {
            if (command.get(command.indexOf("--key") + 1).endsWith("rows.jsonl")) {
                write(Path.of(command.getLast()), "변경된 자료");
            } else {
                copy(source, command);
            }
        });

        // when & then
        assertThatThrownBy(() -> downloader.download(directory, UUID.randomUUID(), source.manifest()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 전송이_실패하면_완료된_묶음으로_취급하지_않는다() {
        // given
        var source = bundle();
        var downloader = new StatisticsExtractDownloader(command -> { throw new IllegalStateException("읽기 실패"); });

        // when & then
        assertThatThrownBy(() -> downloader.download(directory, UUID.randomUUID(), source.manifest()))
            .isInstanceOf(IllegalStateException.class).hasMessage("읽기 실패");
    }

    @Test
    void DB_트랜잭션_안에서는_다운로드를_시작하지_않는다() {
        // given
        var source = bundle();
        var commands = new ArrayList<List<String>>();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        // when & then
        try {
            assertThatThrownBy(() -> new StatisticsExtractDownloader(commands::add)
                .download(directory, UUID.randomUUID(), source.manifest()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("트랜잭션");
            assertThat(commands).isEmpty();
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private StatisticsExtractWriter.Bundle bundle() {
        var until = Instant.parse("2026-10-09T00:00:00Z");
        var scope = new StatisticsInputScope(StatisticsInputScope.SCHEMA_VERSION,
            DemandStatisticsVersion.CURRENT_CALCULATION_VERSION, 1, 1, until, ZoneId.of("Asia/Seoul"), 1);
        var row = new StatisticsInputRow(1, 9, 1, true, ScoringState.LOST, null, null, null, until, 1,
            new StatisticsInputRow.Observation(1, 1, "bus", 0, 20, true), null);
        return StatisticsExtractWriter.write(directory,
            new JdbcStatisticsInputExtractor.Extracted(scope, 0, 1, List.of(row)), 8192);
    }

    private void copy(StatisticsExtractWriter.Bundle source, List<String> command) {
        String key = command.get(command.indexOf("--key") + 1);
        try {
            Files.copy(source.directory().resolve(key.substring(key.lastIndexOf('/') + 1)), Path.of(command.getLast()));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private void write(Path target, String value) {
        try {
            Files.writeString(target, value);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
