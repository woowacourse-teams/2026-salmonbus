package com.gustler.backend.forecasting.infrastructure.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.gustler.backend.forecasting.domain.evaluation.ScoringState;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsVersion;
import com.gustler.backend.forecasting.infrastructure.statistics.JdbcStatisticsInputExtractor.Extracted;
import com.gustler.backend.forecasting.infrastructure.statistics.StatisticsExtractWriter.Bundle;
import com.gustler.backend.forecasting.infrastructure.statistics.StatisticsInputRow.Observation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class StatisticsExtractUploaderTest {
    @TempDir
    Path directory;

    @Test
    void 추출한_자료는_다시_읽을_수_있고_설명서는_자료_뒤에_올린다() throws Exception {
        // given
        Bundle bundle = bundle();
        List<List<String>> commands = new ArrayList<>();
        StatisticsExtractUploader uploader = new StatisticsExtractUploader(commands::add);
        UUID id = UUID.randomUUID();

        // when
        String location = uploader.upload(bundle, id);
        try (StatisticsInputReader reader = new StatisticsInputReader(bundle.directory(), bundle.manifest()
            .files(), 4096)) {
            StatisticsInputRows.verify(bundle.manifest().scope(), reader);
        }

        // then
        assertThat(location).isEqualTo(
            "s3://techcourse-project-2026/salmonbus-be/statistics-input/v1/route-version=1/export-id=" + id + "/");
        assertThat(commands).hasSize(2);
        assertThat(value(commands.get(0), "--key")).endsWith("/rows.jsonl");
        assertThat(value(commands.get(1), "--key")).endsWith("/manifest.json");
        for (List<String> command : commands) {
            assertThat(value(command, "--if-none-match")).isEqualTo("*");
            assertThat(value(command, "--tagging")).contains("ProjectTeam=salmonbus", "Service=techcourse",
            "Role=techcourse-etc");
        }
    }

    @Test
    void 자료_업로드가_실패하면_완료_설명서를_올리지_않는다() {
        // given
        Bundle bundle = bundle();
        List<List<String>> commands = new ArrayList<>();
        StatisticsExtractUploader uploader = new StatisticsExtractUploader(command -> {
            commands.add(command);
            throw new IllegalStateException("전송 실패");
        });

        // when
        Throwable caughtFailure = catchThrowable(() -> uploader.upload(bundle, UUID.randomUUID()));

        // then
        assertThat(caughtFailure)
            .isNotNull().hasMessage("전송 실패");
        assertThat(commands).hasSize(1);
        assertThat(value(commands.getFirst(), "--key")).endsWith("/rows.jsonl");
    }

    @Test
    void 파일이나_설명서가_바뀌면_업로드를_시작하지_않는다() throws Exception {
        // given
        Bundle bundle = bundle();
        List<List<String>> commands = new ArrayList<>();
        StatisticsExtractUploader uploader = new StatisticsExtractUploader(commands::add);
        Files.writeString(bundle.directory().resolve("manifest.json"), "{}");

        // when
        Throwable caughtFailure = catchThrowable(() ->
            uploader.upload(bundle, UUID.randomUUID()));

        // then
        assertThat(caughtFailure)
            .isNotNull().isInstanceOf(IllegalArgumentException.class);
        assertThat(commands).isEmpty();
    }

    @Test
    void DB_트랜잭션이_열려_있으면_외부_전송을_거부한다() {
        // given
        Bundle bundle = bundle();
        List<List<String>> commands = new ArrayList<>();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        // then
        try {

            // when
            Throwable phaseFailure1 = catchThrowable(
                () -> new StatisticsExtractUploader(commands::add).upload(bundle, UUID.randomUUID()));

            // then
            assertThat(phaseFailure1)
                .isNotNull()
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("트랜잭션");
            assertThat(commands).isEmpty();
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void 파일_크기_한도를_넘으면_완료_설명서를_만들지_않는다() throws Exception {
        // given
        Extracted input = extracted();

        // when
        Throwable caughtFailure = catchThrowable(() -> StatisticsExtractWriter.write(directory, input, 1));

        // then
        assertThat(caughtFailure)
            .isNotNull()
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("한도");

        // when
        try (Stream<Path> paths = Files.walk(directory)) {
            final boolean hasNoManifest = paths.noneMatch(
                path -> path.getFileName().toString().equals("manifest.json"));

            // then
            assertThat(hasNoManifest).isTrue();
        }
    }

    private Bundle bundle() {
        return StatisticsExtractWriter.write(directory, extracted(), 8192);
    }

    private Extracted extracted() {
        Instant until = Instant.parse("2026-10-09T00:00:00Z");
        StatisticsInputScope scope = new StatisticsInputScope(StatisticsInputScope.SCHEMA_VERSION,
            DemandStatisticsVersion.CURRENT_CALCULATION_VERSION, 1, 1, until, ZoneId.of("Asia/Seoul"), 1);
        StatisticsInputRow row = new StatisticsInputRow(1, 9, 1, true, ScoringState.LOST, null, null, null, until, 1,
            new Observation(1, 1, "bus", 0, 20, true), null);
        return new Extracted(scope, 0, 1, List.of(row));
    }

    private String value(List<String> command, String option) {
        return command.get(command.indexOf(option) + 1);
    }
}
