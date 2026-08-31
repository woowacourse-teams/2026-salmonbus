package com.gustler.backend.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ArrivalLabelResolverTest {

    private static final Instant FORECAST_AT = Instant.parse("2026-08-25T08:30:00Z");
    private static final long ROUTE_VERSION_3330 = 1L;
    private static final String VEHICLE_ID = "204000206";
    private static final int TARGET_STOP_ORDER = 49;
    private static final int STOPS_TO_TARGET = 5;
    private static final int PASSED_STOP_ORDER = TARGET_STOP_ORDER - STOPS_TO_TARGET;
    private static final long ARRIVAL_OBSERVATION_ID = 7700L;

    /** 아직 기다릴 만한 시각. 예보를 낸 지 10분이다. */
    private static final Instant STILL_WAITING_AT = FORECAST_AT.plusSeconds(600);

    /** 기다림 한도(2시간)를 넘긴 시각. */
    private static final Instant WAITED_TOO_LONG_AT = FORECAST_AT.plusSeconds(2 * 3600 + 1);

    @Test
    void 대상_정류장을_지난_관측의_잔여석이_0이면_만석으로_회수한다() {
        // given
        List<ArrivalCandidate> passing = List.of(passedAt(TARGET_STOP_ORDER, 60, 0));

        // when
        ArrivalLabel actual = ArrivalLabelResolver.resolve(pending(), passing, STILL_WAITING_AT);

        // then
        assertThat(actual).isEqualTo(new ArrivalLabel.Settled(ARRIVAL_OBSERVATION_ID, 0));
    }

    @Test
    void 대상_정류장을_지난_관측의_잔여석이_양수면_여유로_회수한다() {
        // given
        List<ArrivalCandidate> passing = List.of(passedAt(TARGET_STOP_ORDER, 60, 9));

        // when
        ArrivalLabel actual = ArrivalLabelResolver.resolve(pending(), passing, STILL_WAITING_AT);

        // then
        assertThat(actual).isEqualTo(new ArrivalLabel.Settled(ARRIVAL_OBSERVATION_ID, 9));
    }

    @Test
    void 대상_정류장에_아직_안_닿은_여정은_열어_둔다() {
        // given
        List<ArrivalCandidate> stillComing = List.of(
            passedAt(PASSED_STOP_ORDER + 1, 20, 9),
            passedAt(PASSED_STOP_ORDER + 2, 40, 8));

        // when
        ArrivalLabel actual = ArrivalLabelResolver.resolve(pending(), stillComing, STILL_WAITING_AT);

        // then
        assertThat(actual.scoringState()).isEqualTo(ScoringState.PENDING);
    }

    @Test
    void 잔여석을_모르는_도착_관측은_좌석_결측으로_닫는다() {
        // given
        List<ArrivalCandidate> passing = List.of(passedAt(TARGET_STOP_ORDER, 60, null));

        // when
        ArrivalLabel actual = ArrivalLabelResolver.resolve(pending(), passing, STILL_WAITING_AT);

        // then
        assertThat(actual).isEqualTo(new ArrivalLabel.SeatMissing(ARRIVAL_OBSERVATION_ID));
    }

    @Test
    void 대상_순번을_건너뛴_여정은_건너뜀으로_닫는다() {
        // given
        List<ArrivalCandidate> jumped = List.of(
            passedAt(TARGET_STOP_ORDER - 1, 40, 9),
            passedAt(TARGET_STOP_ORDER + 1, 80, 9));

        // when
        ArrivalLabel actual = ArrivalLabelResolver.resolve(pending(), jumped, STILL_WAITING_AT);

        // then
        assertThat(actual.scoringState()).isEqualTo(ScoringState.SKIPPED);
    }

    @Test
    void 관측이_90초보다_길게_끊기면_끊긴_것으로_닫는다() {
        // given
        List<ArrivalCandidate> afterGap = List.of(passedAt(TARGET_STOP_ORDER, 91, 9));

        // when
        ArrivalLabel actual = ArrivalLabelResolver.resolve(pending(), afterGap, STILL_WAITING_AT);

        // then
        assertThat(actual.scoringState()).isEqualTo(ScoringState.LOST);
    }

    @Test
    void 관측_간격이_90초까지는_라벨로_쓴다() {
        // given
        List<ArrivalCandidate> withinGap = List.of(passedAt(TARGET_STOP_ORDER, 90, 9));

        // when
        ArrivalLabel actual = ArrivalLabelResolver.resolve(pending(), withinGap, STILL_WAITING_AT);

        // then
        assertThat(actual.scoringState()).isEqualTo(ScoringState.SETTLED);
    }

    @Test
    void 순번이_되돌아가면_새_여정이라_끊긴_것으로_닫는다() {
        // given
        List<ArrivalCandidate> turnedAround = List.of(
            passedAt(PASSED_STOP_ORDER + 1, 20, 9),
            passedAt(1, 40, 40));

        // when
        ArrivalLabel actual = ArrivalLabelResolver.resolve(pending(), turnedAround, STILL_WAITING_AT);

        // then
        assertThat(actual.scoringState()).isEqualTo(ScoringState.LOST);
    }

    @Test
    void 차량_아이디가_없는_예보는_도착을_찾을_수_없어_끊긴_것으로_닫는다() {
        // given
        PendingForecast withoutVehicleId = new PendingForecast(
            100L, TARGET_STOP_ORDER, ROUTE_VERSION_3330, null, STOPS_TO_TARGET, FORECAST_AT, FORECAST_AT);

        // when
        ArrivalLabel actual = ArrivalLabelResolver.resolve(withoutVehicleId, List.of(), STILL_WAITING_AT);

        // then
        assertThat(actual.scoringState()).isEqualTo(ScoringState.LOST);
    }

    @Test
    void 같은_정류장에_여러_판_머무는_동안은_열어_둔다() {
        // given
        List<ArrivalCandidate> waiting = List.of(
            passedAt(PASSED_STOP_ORDER, 20, 9),
            passedAt(PASSED_STOP_ORDER, 40, 9),
            passedAt(PASSED_STOP_ORDER, 60, 9));

        // when
        ArrivalLabel actual = ArrivalLabelResolver.resolve(pending(), waiting, STILL_WAITING_AT);

        // then
        assertThat(actual.scoringState()).isEqualTo(ScoringState.PENDING);
    }

    @Test
    void 기다림_한도를_넘도록_대상_정류장에_안_닿으면_끊긴_것으로_닫는다() {
        // given
        List<ArrivalCandidate> stillComing = List.of(passedAt(PASSED_STOP_ORDER + 1, 20, 9));

        // when
        ArrivalLabel actual = ArrivalLabelResolver.resolve(pending(), stillComing, WAITED_TOO_LONG_AT);

        // then
        assertThat(actual.scoringState()).isEqualTo(ScoringState.LOST);
    }

    @Test
    void 뒤_관측이_하나도_없어도_한도_전까지는_열어_둔다() {
        // when
        ArrivalLabel actual = ArrivalLabelResolver.resolve(pending(), List.of(), STILL_WAITING_AT);

        // then
        assertThat(actual.scoringState()).isEqualTo(ScoringState.PENDING);
    }

    @Test
    void 운행을_끝내_뒤_관측이_없는_차량은_한도가_지나면_닫힌다() {
        // when
        ArrivalLabel actual = ArrivalLabelResolver.resolve(pending(), List.of(), WAITED_TOO_LONG_AT);

        // then
        assertThat(actual.scoringState()).isEqualTo(ScoringState.LOST);
    }

    private PendingForecast pending() {
        return new PendingForecast(
            100L, TARGET_STOP_ORDER, ROUTE_VERSION_3330, VEHICLE_ID, STOPS_TO_TARGET, FORECAST_AT, FORECAST_AT);
    }

    private ArrivalCandidate passedAt(
        final int passedStopOrder,
        final int secondsAfterForecast,
        Integer remainingSeats
    ) {
        return new ArrivalCandidate(
            ARRIVAL_OBSERVATION_ID,
            new ObservedVehicle(
                VEHICLE_ID,
                ROUTE_VERSION_3330,
                passedStopOrder,
                FORECAST_AT.plusSeconds(secondsAfterForecast),
                remainingSeats));
    }
}
