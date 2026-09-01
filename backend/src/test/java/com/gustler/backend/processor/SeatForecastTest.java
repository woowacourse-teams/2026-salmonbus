package com.gustler.backend.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SeatForecastTest {

    private static final Instant OBSERVED_AT = Instant.parse("2026-08-25T08:30:00Z");
    private static final Instant GENERATED_AT = Instant.parse("2026-08-25T08:30:01Z");
    /** 혼잡도를 안 준 관측. 이 테스트가 보는 것과 무관하다. */
    private static final Integer CROWD_LEVEL_UNKNOWN = null;

    private static final long ROUTE_VERSION_3330 = 1L;
    private static final long OBSERVATION_ID = 100L;
    private static final int DEMAND_STATISTICS_REVISION = 3;

    @Test
    void 지평은_대상_순번에서_차량이_지나온_순번을_뺀_값이다() {
        // given
        VehicleStopTarget target = new VehicleStopTarget(observed(44), routeStop(49));

        // when
        SeatForecast actual = SeatForecast.of(
            OBSERVATION_ID, target, result(), deployment(), DEMAND_STATISTICS_REVISION, GENERATED_AT);

        // then
        assertThat(actual.stopsToTarget()).isEqualTo(5);
    }

    @Test
    void 이동_전_만석_확률과_응답에_쓰는_만석_확률을_둘_다_든다() {
        // given
        VehicleStopTarget target = new VehicleStopTarget(observed(44), routeStop(49));

        // when
        SeatForecast actual = SeatForecast.of(
            OBSERVATION_ID, target, result(), deployment(), DEMAND_STATISTICS_REVISION, GENERATED_AT);

        // then
        assertThat(actual).extracting(SeatForecast::seatFullChanceRaw, SeatForecast::seatFullChance)
            .containsExactly(0.38, 0.4);
    }

    @Test
    void 예보는_대상_정류장의_노선_판본을_그대로_쓴다() {
        // given
        VehicleStopTarget target = new VehicleStopTarget(observed(44), routeStop(49));

        // when
        SeatForecast actual = SeatForecast.of(
            OBSERVATION_ID, target, result(), deployment(), DEMAND_STATISTICS_REVISION, GENERATED_AT);

        // then
        assertThat(actual.routeVersionId()).isEqualTo(ROUTE_VERSION_3330);
    }

    @Test
    void 기대_잔여석은_없어도_예보가_선다() {
        // when
        SeatForecast actual = new SeatForecast(
            OBSERVATION_ID, ROUTE_VERSION_3330, 49, 5, 1L, DEMAND_STATISTICS_REVISION,
            0.38, 0.4, null, GENERATED_AT);

        // then
        assertThat(actual.expectedSeats()).isNull();
    }

    @Test
    void 기대_잔여석은_0석_이상이다() {
        // when & then
        assertThatThrownBy(() -> new SeatForecast(
            OBSERVATION_ID, ROUTE_VERSION_3330, 49, 5, 1L, DEMAND_STATISTICS_REVISION,
            0.38, 0.4, -0.1, GENERATED_AT))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 예보는_1정류장_앞부터_12정류장_앞까지만_낸다() {
        // when & then
        assertThatThrownBy(() -> new SeatForecast(
            OBSERVATION_ID, ROUTE_VERSION_3330, 57, 13, 1L, DEMAND_STATISTICS_REVISION,
            0.38, 0.4, 12.4, GENERATED_AT))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private ObservedVehicle observed(
        final int passedStopOrder
    ) {
        return new ObservedVehicle(
            "204000206", ROUTE_VERSION_3330, passedStopOrder, OBSERVED_AT, 12, CROWD_LEVEL_UNKNOWN);
    }

    private RouteStop routeStop(
        final int stopOrder
    ) {
        return new RouteStop(ROUTE_VERSION_3330, stopOrder, "20400" + stopOrder, true);
    }

    private SeatForecastResult result() {
        return new SeatForecastResult(new SeatDistribution(List.of(0.4, 0.6)), 0.38);
    }

    private ActiveModelDeployment deployment() {
        return new ActiveModelDeployment(1L, "seat-feature-v1", "release-2026-08-19", "0".repeat(64));
    }
}
