package com.gustler.backend.forecasting.domain.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class DemandStatisticsVersionTest {

    private static final long ROUTE_VERSION_ID = 1L;
    private static final String CALCULATION_VERSION = "v1";
    private static final int REVISION = 1;
    private static final Instant DATA_UNTIL = Instant.parse("2026-09-22T00:00:00Z");
    private static final Instant COMPUTED_AT = Instant.parse("2026-09-22T00:01:00Z");

    @Test
    void 전달한_목록을_변경해도_통계_버전은_바뀌지_않는다() {
        // given
        StopDemandMeasurement first = measurement(TimeSlot.MORNING, 1);
        List<StopDemandMeasurement> measurements = new ArrayList<>(List.of(first));
        DemandStatisticsVersion version = versionOf(measurements);

        // when
        measurements.add(measurement(TimeSlot.EVENING, 2));

        // then
        assertThat(version.measurements()).containsExactly(first);
    }

    @Test
    void 저장된_집계_결과를_직접_수정할_수_없다() {
        // given
        DemandStatisticsVersion version = versionOf(List.of(measurement(TimeSlot.MORNING, 1)));

        // when & then
        assertThatThrownBy(() -> version.measurements().clear())
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 같은_시간대의_같은_정류장을_중복_집계할_수_없다() {
        // given
        List<StopDemandMeasurement> measurements = List.of(
            measurement(TimeSlot.MORNING, 1),
            new StopDemandMeasurement(TimeSlot.MORNING, new StopDemandCell(1, 0.7, 0.3, 20, 3))
        );

        // when & then
        assertThatThrownBy(() -> versionOf(measurements))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("중복");
    }

    @Test
    void 시간대나_정류장이_다르면_별도_집계_결과로_저장한다() {
        // given
        List<StopDemandMeasurement> measurements = List.of(
            measurement(TimeSlot.MORNING, 1),
            measurement(TimeSlot.EVENING, 1),
            measurement(TimeSlot.MORNING, 2)
        );

        // when
        DemandStatisticsVersion version = versionOf(measurements);

        // then
        assertThat(version.measurements()).containsExactlyElementsOf(measurements);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void 노선_버전_ID는_양수여야_한다(final long routeVersionId) {
        // given
        List<StopDemandMeasurement> measurements = List.of(measurement(TimeSlot.MORNING, 1));

        // when & then
        assertThatThrownBy(() -> new DemandStatisticsVersion(
            routeVersionId, CALCULATION_VERSION, REVISION, DATA_UNTIL, COMPUTED_AT, measurements
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void 통계_버전은_1부터_시작한다(final int revision) {
        // given
        List<StopDemandMeasurement> measurements = List.of(measurement(TimeSlot.MORNING, 1));

        // when & then
        assertThatThrownBy(() -> new DemandStatisticsVersion(
            ROUTE_VERSION_ID, CALCULATION_VERSION, revision, DATA_UNTIL, COMPUTED_AT, measurements
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void 계산_규칙_버전은_비어_있을_수_없다(String calculationVersion) {
        // given
        List<StopDemandMeasurement> measurements = List.of(measurement(TimeSlot.MORNING, 1));

        // when & then
        assertThatThrownBy(() -> new DemandStatisticsVersion(
            ROUTE_VERSION_ID, calculationVersion, REVISION, DATA_UNTIL, COMPUTED_AT, measurements
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 자료_기준_시각은_계산_완료_시각보다_늦을_수_없다() {
        // given
        List<StopDemandMeasurement> measurements = List.of(measurement(TimeSlot.MORNING, 1));

        // when & then
        assertThatThrownBy(() -> new DemandStatisticsVersion(
            ROUTE_VERSION_ID, CALCULATION_VERSION, REVISION, COMPUTED_AT, DATA_UNTIL, measurements
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 자료_기준_시각과_계산_완료_시각이_같아도_된다() {
        // given
        List<StopDemandMeasurement> measurements = List.of(measurement(TimeSlot.MORNING, 1));

        // when
        DemandStatisticsVersion version = new DemandStatisticsVersion(
            ROUTE_VERSION_ID, CALCULATION_VERSION, REVISION, DATA_UNTIL, DATA_UNTIL, measurements
        );

        // then
        assertThat(version.dataUntil()).isEqualTo(version.computedAt());
    }

    @Test
    void 자료_기준_시각과_계산_완료_시각이_모두_필요하다() {
        // given
        List<StopDemandMeasurement> measurements = List.of(measurement(TimeSlot.MORNING, 1));

        // when & then
        assertThatThrownBy(() -> new DemandStatisticsVersion(
            ROUTE_VERSION_ID, CALCULATION_VERSION, REVISION, null, COMPUTED_AT, measurements
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DemandStatisticsVersion(
            ROUTE_VERSION_ID, CALCULATION_VERSION, REVISION, DATA_UNTIL, null, measurements
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 집계_결과가_없으면_통계_버전을_만들_수_없다() {
        // when & then
        assertThatThrownBy(() -> versionOf(List.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> versionOf(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 집계_결과와_그_안의_시간대와_정류장_통계는_null일_수_없다() {
        // given
        List<StopDemandMeasurement> missingMeasurement = Arrays.asList((StopDemandMeasurement) null);
        StopDemandMeasurement missingTimeSlot = new StopDemandMeasurement(null, cell(1));
        StopDemandMeasurement missingCell = new StopDemandMeasurement(TimeSlot.MORNING, null);

        // when & then
        assertThatThrownBy(() -> versionOf(missingMeasurement))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> versionOf(List.of(missingTimeSlot)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> versionOf(List.of(missingCell)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private DemandStatisticsVersion versionOf(List<StopDemandMeasurement> measurements) {
        return new DemandStatisticsVersion(
            ROUTE_VERSION_ID, CALCULATION_VERSION, REVISION, DATA_UNTIL, COMPUTED_AT, measurements
        );
    }

    private StopDemandMeasurement measurement(TimeSlot timeSlot, final int stopOrder) {
        return new StopDemandMeasurement(timeSlot, cell(stopOrder));
    }

    private StopDemandCell cell(final int stopOrder) {
        return new StopDemandCell(stopOrder, 0.5, 0.1, 10, 2);
    }
}
