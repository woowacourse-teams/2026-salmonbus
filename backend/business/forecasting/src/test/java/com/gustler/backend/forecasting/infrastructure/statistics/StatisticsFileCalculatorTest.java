package com.gustler.backend.forecasting.infrastructure.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.gustler.backend.forecasting.domain.evaluation.ScoringState;
import com.gustler.backend.forecasting.domain.statistics.DailyStopDemand;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsVersion;
import com.gustler.backend.forecasting.domain.statistics.StopDemandCellTotals;
import com.gustler.backend.forecasting.domain.statistics.VehicleHourlyDemand;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class StatisticsFileCalculatorTest {
    private static final Instant UNTIL = Instant.parse("2026-10-09T00:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    @TempDir Path directory;

    @Test
    void 파일_계산은_기존_시간별_집계와_같은_통계를_만든다() throws Exception {
        // given
        var rows = List.of(row(10, 40, 10, true, 3600), row(20, 20, 5, true, 3600));
        var files = write(rows);
        var samples = rows.stream().map(row -> row.sample(scope(2)).orElseThrow()).toList();
        DailyStopDemand day = VehicleHourlyDemand.sumOf(samples).getFirst().onDay(40, Clock.fixed(UNTIL, ZONE));
        var expected = StopDemandCellTotals.ofDay(day).toMeasurement();

        // when
        var actual = StatisticsFileCalculator.calculate(directory, files, scope(2), Map.of("bus", 40), 100, 4096);

        // then
        assertThat(actual).hasSize(1);
        assertThat(actual.getFirst().cell().averageFillRate()).isCloseTo(expected.cell().averageFillRate(), within(1e-12));
        assertThat(actual.getFirst().cell().averageNetBoardingRate()).isCloseTo(expected.cell().averageNetBoardingRate(), within(1e-12));
        assertThat(actual.getFirst().cell().sampleCount()).isEqualTo(expected.cell().sampleCount());
        assertThat(actual.getFirst().timeSlot()).isEqualTo(expected.timeSlot());
    }

    @Test
    void 관측이_많은_날과_적은_날을_같은_비중으로_계산한다() throws Exception {
        // given
        var files = write(List.of(row(10, 40, 0, true, 3600), row(20, 40, 0, true, 3600),
            row(30, 40, 40, true, 90000)));

        // when
        var actual = StatisticsFileCalculator.calculate(directory, files, scope(3), Map.of("bus", 40), 100, 4096);

        // then
        assertThat(actual).hasSize(1);
        assertThat(actual.getFirst().cell().averageFillRate()).isEqualTo(0.5);
        assertThat(actual.getFirst().cell().averageNetBoardingRate()).isEqualTo(0.5);
        assertThat(actual.getFirst().cell().dayCount()).isEqualTo(2);
        assertThat(actual.getFirst().cell().sampleCount()).isEqualTo(3);
    }

    @Test
    void 품질에서_제외된_관측은_파일에_있어도_통계에_넣지_않는다() throws Exception {
        // given
        var files = write(List.of(row(10, 40, 10, false, 3600), row(20, 20, 5, true, 3600)));

        // when
        var actual = StatisticsFileCalculator.calculate(directory, files, scope(2), Map.of("bus", 20), 100, 4096);

        // then
        assertThat(actual.getFirst().cell().sampleCount()).isEqualTo(1);
        assertThat(actual.getFirst().cell().averageFillRate()).isEqualTo(0.75);
    }

    @Test
    void 정원이_없거나_관측한_잔여석보다_작으면_결과를_반환하지_않는다() throws Exception {
        // given
        var files = write(List.of(row(10, 40, 10, true, 3600)));

        // when & then
        assertThatThrownBy(() -> StatisticsFileCalculator.calculate(directory, files, scope(1), Map.of(), 100, 4096))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("정원");
        assertThatThrownBy(() -> StatisticsFileCalculator.calculate(directory, files, scope(1), Map.of("bus", 20), 100, 4096))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("정원");
    }

    @Test
    void 집계_한도를_넘으면_자료를_잘라_결과를_만들지_않고_실패한다() throws Exception {
        // given
        var files = write(List.of(row(10, 40, 10, true, 3600), row(20, 40, 10, true, 90000)));

        // when & then
        assertThatThrownBy(() -> StatisticsFileCalculator.calculate(directory, files, scope(2), Map.of("bus", 40), 1, 4096))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("한도");
    }

    @Test
    void 마지막에_중복이나_건수_불일치가_발견되어도_부분_결과를_반환하지_않는다() throws Exception {
        // given
        var input = row(10, 40, 10, true, 3600);
        var files = write(List.of(input, input));

        // when & then
        assertThatThrownBy(() -> StatisticsFileCalculator.calculate(directory, files, scope(2), Map.of("bus", 40), 100, 4096))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("중복");
        var single = write(List.of(input));
        assertThatThrownBy(() -> StatisticsFileCalculator.calculate(directory, single, scope(2), Map.of("bus", 40), 100, 4096))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("건수");
    }

    private List<StatisticsInputFile> write(List<StatisticsInputRow> rows) throws Exception {
        var mapper = JsonMapper.builder().build();
        var path = directory.resolve("rows.jsonl");
        try (var output = Files.newBufferedWriter(path)) {
            for (var row : rows) { output.write(mapper.writeValueAsString(row)); output.newLine(); }
        }
        return List.of(new StatisticsInputFile("rows.jsonl", Files.size(path),
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)))));
    }

    private StatisticsInputScope scope(long count) {
        return new StatisticsInputScope(StatisticsInputScope.SCHEMA_VERSION,
            DemandStatisticsVersion.CURRENT_CALCULATION_VERSION, 1, 1, UNTIL, ZONE, count);
    }

    private StatisticsInputRow row(long id, int before, int after, boolean usable, long secondsAgo) {
        return new StatisticsInputRow(id, 9, 1, true, ScoringState.SETTLED, id + 1000, after,
            UNTIL.minusSeconds(secondsAgo), UNTIL, 1,
            new StatisticsInputRow.Observation(id, 1, "bus", 0, before, usable),
            new StatisticsInputRow.Observation(id + 1000, 1, "bus", 0, after, usable));
    }
}
