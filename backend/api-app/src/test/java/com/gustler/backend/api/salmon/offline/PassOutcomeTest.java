package com.gustler.backend.api.salmon.offline;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.salmon.offline.ObservationTrail.Timeliness;
import com.gustler.backend.api.salmon.offline.OutcomeTally.Boarding;
import com.gustler.backend.api.salmon.offline.PassOutcome.Observation;
import com.gustler.backend.api.salmon.offline.PassOutcome.Seats;
import com.gustler.backend.api.salmon.offline.PassOutcome.Unknown;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/**
 * 첫 관측 기준(first)이 제품 정산(ArrivalLabelResolver)과 같은 관측을 고르는지 본다. forecasting 모듈은 api-app의 의존이
 * 아니라 그 클래스를 부를 수 없어서, ArrivalLabelResolverTest의 사례(대상 49, 예보 때 통과 순번 44)를 같은 입력으로 옮겨
 * 같은 결과를 기대한다. 제품의 SETTLED는 좌석 수, SEAT_MISSING은 SEAT_MISSING, SKIPPED는 SKIPPED, LOST는 LOST,
 * 아직 닿지 않음(PENDING)은 NOT_REACHED에 대응한다.
 */
class PassOutcomeTest {
    private static final Instant START = Instant.parse("2026-08-25T08:30:00Z");
    private static final int TARGET = 49;
    private static final OptionalInt NEXT_BOARDING_STOP = OptionalInt.of(51);

    @Test
    void 대상_정류장을_지난_첫_관측의_좌석을_읽는다() {
        assertThat(first(passed(49, 60, 0))).isEqualTo(Seats.of(0));
        assertThat(first(passed(49, 60, 9))).isEqualTo(Seats.of(9));
        assertThat(first(passed(45, 20, 9), passed(49, 80, 3), passed(49, 140, 7))).isEqualTo(Seats.of(3));
    }

    @Test
    void 좌석을_모르는_관측이면_좌석_결측이다() {
        assertThat(first(passed(49, 60, null))).isEqualTo(Seats.unknown(Unknown.SEAT_MISSING));
    }

    @Test
    void 대상_순번에_아직_닿지_않았거나_뒤_관측이_없으면_닿지_않음이다() {
        assertThat(first(passed(45, 20, 9), passed(46, 40, 9))).isEqualTo(Seats.unknown(Unknown.NOT_REACHED));
        assertThat(first(passed(44, 20, 9), passed(44, 40, 9), passed(44, 60, 9)))
            .isEqualTo(Seats.unknown(Unknown.NOT_REACHED));
        assertThat(first()).isEqualTo(Seats.unknown(Unknown.NOT_REACHED));
    }

    @Test
    void 대상_순번을_건너뛰면_건너뜀이다() {
        assertThat(first(passed(48, 30, 9), passed(50, 60, 9))).isEqualTo(Seats.unknown(Unknown.SKIPPED));
    }

    @Test
    void 관측_간격이_90초를_넘으면_끊기고_90초까지는_잇는다() {
        assertThat(first(passed(49, 91, 0))).isEqualTo(Seats.unknown(Unknown.LOST));
        assertThat(first(passed(49, 90, 0))).isEqualTo(Seats.of(0));
        assertThat(first(passed(46, 60, 9), passed(49, 151, 0))).isEqualTo(Seats.unknown(Unknown.LOST));
    }

    @Test
    void 통과_순번이_되돌아가거나_운행_방향이_바뀌면_끊긴다() {
        assertThat(first(passed(46, 30, 9), passed(45, 60, 9), passed(49, 90, 0)))
            .isEqualTo(Seats.unknown(Unknown.LOST));
        assertThat(first(new Observation(START.plusSeconds(60), 49, 0, 1L))).isEqualTo(Seats.unknown(Unknown.LOST));
    }

    @Test
    void 운행_방향이_없는_관측을_만나면_끊긴다() {
        Observation ineligible = new Observation(START.plusSeconds(60), 49, 5, null);

        assertThat(first(ineligible)).isEqualTo(Seats.unknown(Unknown.LOST));
        assertThat(first(passed(46, 30, 9), ineligible)).isEqualTo(Seats.unknown(Unknown.LOST));
    }

    @Test
    void 운행_방향이_0이_아니어도_출발점과_같은_방향이면_잇고_다르면_끊는다() {
        Optional<Observation> downward = Optional.of(new Observation(START, 44, 9, 1L));
        List<Observation> sameDirection = List.of(new Observation(START.plusSeconds(60), 49, 3, 1L));
        List<Observation> turned = List.of(new Observation(START.plusSeconds(60), 49, 3, 0L));

        assertThat(PassOutcome.resolve(downward, sameDirection, TARGET, NEXT_BOARDING_STOP, Optional.empty()).first())
            .isEqualTo(Seats.of(3));
        assertThat(PassOutcome.resolve(downward, turned, TARGET, NEXT_BOARDING_STOP, Optional.empty()).first())
            .isEqualTo(Seats.unknown(Unknown.LOST));
    }

    @Test
    void 출발점의_운행_방향이_없으면_방향을_보지_않는다() {
        Optional<Observation> ineligibleStart = Optional.of(new Observation(START, 44, 9, null));

        assertThat(PassOutcome.resolve(ineligibleStart, List.of(passed(49, 60, 3)), TARGET, NEXT_BOARDING_STOP,
            Optional.empty()).first()).isEqualTo(Seats.of(3));
    }

    @Test
    void 예보_계산_시각까지의_관측은_건너뛰고_관측_간격은_출발점부터_잰다() {
        Optional<Observation> start = Optional.of(new Observation(START, 44, 9, 0L));
        List<Observation> later = List.of(passed(49, 10, 0), passed(49, 80, 4));

        assertThat(PassOutcome.resolve(start, later, TARGET, NEXT_BOARDING_STOP, Optional.empty()).first())
            .isEqualTo(Seats.of(0));
        assertThat(PassOutcome.resolve(start, later, TARGET, NEXT_BOARDING_STOP,
            Optional.of(START.plusSeconds(10))).first()).isEqualTo(Seats.of(4));
        assertThat(PassOutcome.resolve(start, List.of(passed(49, 10, 0), passed(49, 95, 4)), TARGET,
            NEXT_BOARDING_STOP, Optional.of(START.plusSeconds(10))).first()).isEqualTo(Seats.unknown(Unknown.LOST));
    }

    @Test
    void 창_안에_떠난_차량은_순번이_흔들려도_한_번만_세고_앞_관측과_이어지지_않으면_좌석을_모른다() {
        List<Observation> wobbling = List.of(passed(48, 0, 9), passed(49, 30, 2), passed(48, 60, 2),
            passed(49, 90, 0));
        List<Observation> alreadyLeft = List.of(passed(49, 0, 7), passed(49, 30, 7));
        List<Observation> afterGap = List.of(passed(48, 0, 9), passed(49, 200, 1));
        List<Observation> afterIneligible = List.of(new Observation(START, 48, 9, null), passed(49, 30, 1));

        assertThat(ObservationTrail.passages(List.of(wobbling, alreadyLeft), TARGET, START,
            START.plusSeconds(900))).containsExactly(Seats.of(2));
        assertThat(ObservationTrail.passages(List.of(afterGap, afterIneligible), TARGET, START,
            START.plusSeconds(900))).containsExactly(Seats.unknown(Unknown.LOST), Seats.unknown(Unknown.LOST));
    }

    @Test
    void 창_안에_그_정류장_순번을_건너뛴_차량은_좌석을_모르는_통과로_남겨_실패로_확정하지_않는다() {
        List<Observation> full = List.of(passed(48, 0, 9), passed(49, 30, 0));
        List<Observation> skipping = List.of(passed(48, 0, 9), passed(50, 30, 4));
        List<Observation> farAhead = List.of(passed(60, 0, 3), passed(61, 30, 3));

        List<Seats> passages = ObservationTrail.passages(List.of(full, skipping, farAhead), TARGET, START,
            START.plusSeconds(900));

        assertThat(passages).containsExactly(Seats.of(0), Seats.unknown(Unknown.SKIPPED));
        assertThat(OutcomeTally.stayOf(passages.stream().map(Boarding::of).toList(), "NO_PASSAGE"))
            .isEqualTo("UNKNOWN");
    }

    @Test
    void 마감_뒤에도_못_미친_차량은_빈자리가_있어도_탈_수_없었고_마감을_사이에_두고_닿은_차량은_모른다() {
        Instant deadline = START.plusSeconds(60);
        List<Observation> late = List.of(passed(48, 0, 9), passed(48, 70, 9), passed(49, 110, 2));
        List<Observation> straddling = List.of(passed(48, 0, 9), passed(48, 50, 9), passed(49, 70, 2));
        List<Observation> onTime = List.of(passed(48, 0, 9), passed(49, 60, 2));

        assertThat(ObservationTrail.timeliness(late, TARGET, START, deadline)).isEqualTo(Timeliness.LATE);
        assertThat(ObservationTrail.timeliness(straddling, TARGET, START, deadline)).isEqualTo(Timeliness.UNSURE);
        assertThat(ObservationTrail.timeliness(onTime, TARGET, START, deadline)).isEqualTo(Timeliness.IN_TIME);
        assertThat(ObservationTrail.timeliness(List.of(), TARGET, START, deadline)).isEqualTo(Timeliness.UNSURE);

        assertThat(OutcomeTally.stayOf(List.of(Boarding.of(Seats.of(2), Timeliness.LATE)), "NO_ATTEMPT"))
            .isEqualTo("FAIL");
        assertThat(OutcomeTally.stayOf(List.of(Boarding.of(Seats.of(2), Timeliness.UNSURE)), "NO_ATTEMPT"))
            .isEqualTo("UNKNOWN");
        assertThat(OutcomeTally.stayOf(List.of(Boarding.of(Seats.of(0), Timeliness.UNSURE)), "NO_ATTEMPT"))
            .isEqualTo("FAIL");
        assertThat(OutcomeTally.stayOf(List.of(Boarding.of(Seats.of(2), Timeliness.IN_TIME)), "NO_ATTEMPT"))
            .isEqualTo("SUCCESS");
    }

    @Test
    void 마지막_관측_기준은_다음_승차_정류장_전의_마지막_관측을_읽고_그_정류장을_지난_관측이_있어야_확정한다() {
        PassOutcome confirmed = outcome(passed(49, 60, 0), passed(49, 120, 2), passed(50, 180, 4),
            passed(51, 240, 9));
        PassOutcome notYet = outcome(passed(49, 60, 0), passed(49, 120, 2));

        assertThat(confirmed.first()).isEqualTo(Seats.of(0));
        assertThat(confirmed.last()).isEqualTo(Seats.of(4));
        assertThat(notYet.first()).isEqualTo(Seats.of(0));
        assertThat(notYet.last()).isEqualTo(Seats.unknown(Unknown.NOT_CONFIRMED));
    }

    @Test
    void 기준_시각에_이미_그_정류장을_지난_차량과_관측이_없는_차량은_결과를_모른다() {
        Optional<Observation> alreadyThere = Optional.of(new Observation(START, 49, 5, 0L));

        assertThat(PassOutcome.resolve(alreadyThere, List.of(passed(49, 60, 0)), TARGET, NEXT_BOARDING_STOP,
            Optional.empty())).isEqualTo(PassOutcome.unknown(Unknown.ALREADY_PASSED));
        assertThat(PassOutcome.resolve(Optional.empty(), List.of(), TARGET, NEXT_BOARDING_STOP, Optional.empty()))
            .isEqualTo(PassOutcome.unknown(Unknown.NO_OBSERVATION));
    }

    private static Seats first(Observation... later) {
        return outcome(later).first();
    }

    private static PassOutcome outcome(Observation... later) {
        return PassOutcome.resolve(Optional.of(new Observation(START, 44, 9, 0L)), List.of(later), TARGET,
            NEXT_BOARDING_STOP, Optional.empty());
    }

    private static Observation passed(int passedStopOrder, int secondsAfterStart, Integer seats) {
        return new Observation(START.plusSeconds(secondsAfterStart), passedStopOrder, seats, 0L);
    }
}
