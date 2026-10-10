package com.gustler.backend.forecasting.infrastructure.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.forecasting.domain.evaluation.ScoringState;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsVersion;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class StatisticsInputReaderTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant UNTIL = Instant.parse("2026-10-09T00:00:00Z");
    @TempDir Path directory;

    @Test
    void 여러_파일을_선언한_순서대로_읽고_마지막_줄바꿈이_없어도_검증한다() throws Exception {
        // given
        var files = List.of(file("first.jsonl", json(10) + "\n"), file("second.jsonl", json(20)));

        // when & then
        try (var reader = new StatisticsInputReader(directory, files, 4096)) {
            assertThat(reader.hasNext()).isTrue();
            assertThat(reader.hasNext()).isTrue();
            assertThat(reader.next()).isEqualTo(row(10));
            assertThat(reader.next()).isEqualTo(row(20));
            assertThat(reader.hasNext()).isFalse();
            assertThat(reader.hasNext()).isFalse();
        }
    }

    @Test
    void 파일_경계를_넘어_중복된_정산도_검증에서_거부한다() throws Exception {
        // given
        var files = List.of(file("first.jsonl", json(10)), file("second.jsonl", json(10)));

        // when & then
        try (var reader = new StatisticsInputReader(directory, files, 4096)) {
            assertThatThrownBy(() -> StatisticsInputRows.verify(scope(2), reader))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("중복");
        }
    }

    @Test
    void 파일을_모두_읽은_뒤_선언한_건수와_다르면_거부한다() throws Exception {
        // given
        var files = List.of(file("rows.jsonl", json(10)));

        // when & then
        try (var reader = new StatisticsInputReader(directory, files, 4096)) {
            assertThatThrownBy(() -> StatisticsInputRows.verify(scope(2), reader))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("건수");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{}", "{broken", "[]"})
    void 손상되거나_비어_있는_행은_정상_자료로_취급하지_않는다(String invalid) throws Exception {
        // given
        var files = List.of(file("rows.jsonl", invalid + "\n"));

        // when & then
        try (var reader = new StatisticsInputReader(directory, files, 4096)) {
            assertThatThrownBy(reader::hasNext).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void 중복된_필드나_누락된_필드는_기본값으로_대체하지_않는다() throws Exception {
        // given
        var valid = json(10);
        var invalidRows = List.of(
            valid.replace("\"predictionObservationId\":10", "\"predictionObservationId\":10.5"),
            valid.replace("\"boardingAllowed\":true", "\"boardingAllowed\":true,\"boardingAllowed\":false"),
            valid.replace("\"boardingAllowed\":true,", ""),
            valid.replace("\"boardingAllowed\":true", "\"boardingAllowed\":null"),
            valid.replace("\"boardingAllowed\":true", "\"boardingAllowed\":\"true\""),
            valid + " {}",
            valid.replace("\"boardingAllowed\":true", "\"unknown\":true,\"boardingAllowed\":true"));

        // when & then
        for (var invalid : invalidRows) {
            var files = List.of(file("rows.jsonl", invalid));
            try (var reader = new StatisticsInputReader(directory, files, 4096)) {
                assertThatThrownBy(reader::hasNext).isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    @Test
    void 지나치게_긴_행은_전체를_메모리에_올리기_전에_거부한다() throws Exception {
        // given
        var files = List.of(file("rows.jsonl", "x".repeat(8192)));

        // when & then
        try (var reader = new StatisticsInputReader(directory, files, 1024)) {
            assertThatThrownBy(reader::hasNext).isInstanceOf(IllegalArgumentException.class)
                .hasRootCauseMessage("자료 행이 최대 길이를 초과했다");
        }
    }

    @Test
    void 선언한_빈_파일은_표본이_없는_입력으로_읽는다() throws Exception {
        // given
        var files = List.of(file("rows.jsonl", ""));

        // when & then
        try (var reader = new StatisticsInputReader(directory, files, 4096)) {
            StatisticsInputRows.verify(scope(0), reader);
            assertThat(reader.hasNext()).isFalse();
        }
    }

    @Test
    void 읽는_도중_파일이_바뀌면_완료된_입력으로_인정하지_않는다() throws Exception {
        // given
        var files = List.of(file("rows.jsonl", json(10)));
        try (var reader = new StatisticsInputReader(directory, files, 4096)) {
            reader.next();
            Files.writeString(directory.resolve("rows.jsonl"), json(20));

            // when & then
            assertThatThrownBy(reader::hasNext).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private StatisticsInputFile file(String name, String text) throws Exception {
        var path = directory.resolve(name);
        Files.writeString(path, text);
        return new StatisticsInputFile(name, Files.size(path),
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
    }

    private String json(long id) { return JSON.writeValueAsString(row(id)); }

    private StatisticsInputRow row(long id) {
        return new StatisticsInputRow(id, 9, 1, true, ScoringState.SETTLED, 100L, 5,
            UNTIL.minusSeconds(60), UNTIL, 1,
            new StatisticsInputRow.Observation(id, 1, "bus", 0, 20, true),
            new StatisticsInputRow.Observation(100, 1, "bus", 0, 5, true));
    }

    private StatisticsInputScope scope(long count) {
        return new StatisticsInputScope(StatisticsInputScope.SCHEMA_VERSION,
            DemandStatisticsVersion.CURRENT_CALCULATION_VERSION, 1, 1, UNTIL, ZoneId.of("Asia/Seoul"), count);
    }
}
