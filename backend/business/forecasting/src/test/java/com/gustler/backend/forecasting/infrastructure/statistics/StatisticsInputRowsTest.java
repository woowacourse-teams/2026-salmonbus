package com.gustler.backend.forecasting.infrastructure.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.forecasting.domain.evaluation.ScoringState;
import com.gustler.backend.forecasting.domain.statistics.DemandSample;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsVersion;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class StatisticsInputRowsTest {

    private static final Instant CUTOFF = Instant.parse("2026-10-09T00:00:00Z");

    @Test
    void 정상_정산은_기존_통계_표본으로_변환한다() {
        // given
        var input = row(10, 9, 1, true, true, CUTOFF);

        // when
        var actual = input.sample(scope(1));

        // then
        assertThat(actual).contains(new DemandSample(1, 10, 100, "bus", 9,
            CUTOFF.minusSeconds(60), CUTOFF, 20, 5));
    }

    @Test
    void 같은_관측의_같은_목표_정류장이_중복되면_검사에_실패한다() {
        // given
        var input = row(10, 9, 1, true, true, CUTOFF);
        var rows = List.of(input, input);

        // when & then
        assertThatThrownBy(() -> StatisticsInputRows.verify(scope(2), rows.iterator()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("중복");
    }

    @Test
    void 같은_관측이라도_목표_정류장이_다르면_중복이_아니다() {
        // given
        var rows = List.of(row(10, 9, 1, true, true, CUTOFF),
            row(10, 10, 2, true, true, CUTOFF));

        // when & then
        assertThatCode(() -> StatisticsInputRows.verify(scope(2), rows.iterator()))
            .doesNotThrowAnyException();
    }

    @Test
    void 정산이_참조하는_관측이_없으면_자료를_만들_수_없다() {
        // given
        var source = observation(10, true);

        // when & then
        assertThatThrownBy(() -> settled(source, null, CUTOFF))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("도착 관측");
        assertThatThrownBy(() -> settled(null, observation(100, true), CUTOFF))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("원 관측");
    }

    @Test
    void 참조한_관측과_실제로_연결한_관측이_다르면_자료를_만들_수_없다() {
        // given
        var wrongArrival = observation(200, true);

        // when & then
        assertThatThrownBy(() -> settled(observation(10, true), wrongArrival, CUTOFF))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("도착 관측");
    }

    @Test
    void 원_관측이_다른_노선이면_검사에_실패한다() {
        // given
        var source = new StatisticsInputRow.Observation(10, 2, "bus", 0, 20, true);
        var input = settled(source, observation(100, true), CUTOFF);

        // when & then
        assertThatThrownBy(() -> input.sample(scope(1)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("노선");
    }

    @Test
    void 자료와_묶음의_품질_판본이_다르면_검사에_실패한다() {
        // given
        var input = row(10, 9, 1, true, true, CUTOFF);
        var differentQuality = new StatisticsInputScope(StatisticsInputScope.SCHEMA_VERSION,
            DemandStatisticsVersion.CURRENT_CALCULATION_VERSION, 1, 2, CUTOFF,
            ZoneId.of("Asia/Seoul"), 1);

        // when & then
        assertThatThrownBy(() -> input.sample(differentQuality))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("품질");
    }

    @Test
    void 문제_관측은_표본에서_제외하고_정상_관측은_표본으로_남긴다() {
        // given
        var normal = row(10, 9, 1, true, true, CUTOFF);
        var excludedSource = row(10, 9, 1, true, false, CUTOFF);
        var excludedArrival = settled(observation(10, true), observation(100, false), CUTOFF);

        // when & then
        assertThat(normal.sample(scope(1))).isPresent();
        assertThat(excludedSource.sample(scope(1))).isEmpty();
        assertThat(excludedArrival.sample(scope(1))).isEmpty();
    }

    @Test
    void 다음_정류장이_아니거나_승차할_수_없으면_표본에서_제외한다() {
        // given
        var distant = row(10, 10, 2, true, true, CUTOFF);
        var noBoarding = row(10, 9, 1, false, true, CUTOFF);

        // when & then
        assertThat(distant.sample(scope(1))).isEmpty();
        assertThat(noBoarding.sample(scope(1))).isEmpty();
    }

    @Test
    void 기준_시각까지_정산된_자료만_이번_표본에_포함한다() {
        // given
        var onTime = row(10, 9, 1, true, true, CUTOFF);
        var late = row(10, 9, 1, true, true, CUTOFF.plusSeconds(1));

        // when & then
        assertThat(onTime.sample(scope(1))).isPresent();
        assertThat(late.sample(scope(1))).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = ScoringState.class, names = "SETTLED", mode = EnumSource.Mode.EXCLUDE)
    void 정상_정산이_아니면_도착_정보_없이도_표본에서_제외한다(ScoringState state) {
        // given
        var input = new StatisticsInputRow(10, 9, 1, true, state,
            null, null, null, null, 1, observation(10, true), null);

        // when
        var actual = input.sample(scope(1));

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 도착_관측의_차량이나_노선이나_방향이_다르면_표본에서_제외한다() {
        // given
        var arrivals = List.of(
            new StatisticsInputRow.Observation(100, 1, "other-bus", 0, 5, true),
            new StatisticsInputRow.Observation(100, 2, "bus", 0, 5, true),
            new StatisticsInputRow.Observation(100, 1, "bus", 1, 5, true));

        // when & then
        for (var arrival : arrivals) {
            assertThat(settled(observation(10, true), arrival, CUTOFF).sample(scope(1))).isEmpty();
        }
    }

    @Test
    void 설명한_건수보다_자료가_적거나_많으면_검사에_실패한다() {
        // given
        var rows = List.of(row(10, 9, 1, true, true, CUTOFF));

        // when & then
        assertThatThrownBy(() -> StatisticsInputRows.verify(scope(0), rows.iterator()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("건수");
        assertThatThrownBy(() -> StatisticsInputRows.verify(scope(2), rows.iterator()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("건수");
    }

    @Test
    void 자료가_없는_묶음도_설명한_건수와_같으면_검사를_통과한다() {
        // given
        List<StatisticsInputRow> rows = List.of();

        // when & then
        assertThatCode(() -> StatisticsInputRows.verify(scope(0), rows.iterator()))
            .doesNotThrowAnyException();
    }

    @Test
    void 정렬되지_않은_자료는_중복을_놓치지_않도록_거부한다() {
        // given
        var rows = List.of(row(20, 9, 1, true, true, CUTOFF),
            row(10, 9, 1, true, true, CUTOFF));

        // when & then
        assertThatThrownBy(() -> StatisticsInputRows.verify(scope(2), rows.iterator()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("정렬");
    }

    @Test
    void 지원하지_않는_자료_형식이나_계산_규칙은_사용할_수_없다() {
        // given
        ZoneId zone = ZoneId.of("Asia/Seoul");

        // when & then
        assertThatThrownBy(() -> new StatisticsInputScope("unsupported",
            DemandStatisticsVersion.CURRENT_CALCULATION_VERSION, 1, 1, CUTOFF, zone, 0))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("자료 형식");
        assertThatThrownBy(() -> new StatisticsInputScope(StatisticsInputScope.SCHEMA_VERSION,
            "unsupported", 1, 1, CUTOFF, zone, 0))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("계산 규칙");
    }

    private StatisticsInputScope scope(long count) {
        return new StatisticsInputScope(StatisticsInputScope.SCHEMA_VERSION,
            DemandStatisticsVersion.CURRENT_CALCULATION_VERSION, 1, 1, CUTOFF,
            ZoneId.of("Asia/Seoul"), count);
    }

    private StatisticsInputRow.Observation observation(long id, boolean usable) {
        return new StatisticsInputRow.Observation(id, 1, "bus", 0, 20, usable);
    }

    private StatisticsInputRow settled(StatisticsInputRow.Observation source,
        StatisticsInputRow.Observation arrival, Instant scoredAt) {
        return new StatisticsInputRow(10, 9, 1, true, ScoringState.SETTLED, 100L, 5,
            CUTOFF.minusSeconds(60), scoredAt, 1, source, arrival);
    }

    private StatisticsInputRow row(long sourceId, int target, int distance,
        boolean boarding, boolean usable, Instant scoredAt) {
        return new StatisticsInputRow(sourceId, target, distance, boarding, ScoringState.SETTLED,
            100L, 5, CUTOFF.minusSeconds(60), scoredAt, 1,
            observation(sourceId, usable), observation(100, true));
    }
}
