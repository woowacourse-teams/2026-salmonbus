package com.gustler.backend.api.salmon.domain;

import static com.gustler.backend.api.salmon.domain.ActionFixture.config;
import static com.gustler.backend.api.salmon.domain.ActionFixture.entry;
import static com.gustler.backend.api.salmon.domain.ActionFixture.marginal;
import static com.gustler.backend.api.salmon.domain.ActionFixture.minutes;
import static com.gustler.backend.api.salmon.domain.ActionFixture.observed;
import static com.gustler.backend.api.salmon.domain.ActionFixture.offsetOf;
import static com.gustler.backend.api.salmon.domain.ActionFixture.queried;
import static com.gustler.backend.api.salmon.domain.ActionFixture.seatForecast;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.gustler.backend.api.salmon.domain.ActionJudge.Failed;
import com.gustler.backend.api.salmon.domain.Candidates.*;
import com.gustler.backend.api.salmon.domain.Fields.*;
import com.gustler.backend.api.salmon.domain.Probabilities.*;
import com.gustler.backend.api.salmon.domain.StopLists.*;
import com.gustler.backend.api.salmon.domain.Timing.*;
import com.gustler.backend.api.salmon.domain.Verdicts.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ActionJudgeTest {
    private static final double TOLERANCE = 1e-9;

    @Test
    void 재탑승_가상_픽스처는_GO이고_연결_근거_부족을_기록한다() {
        ActionFixture fixture = new ActionFixture();
        Verdict verdict = ActionFixture.judge(fixture.baseline(), config(20, 8),
            fixture.source(fixture.walkB(), fixture.walkF(), fixture.reboardCandidate()));

        assertThat(verdict.code()).isEqualTo(ReasonCode.GO);
        assertThat(verdict.bestCandidate()).contains("reboard");
        assertThat(verdict.delta().orElseThrow()).isCloseTo(0.347, within(TOLERANCE));
        assertThat(verdict.partialComparison()).isFalse();
        Evaluable stay = (Evaluable) verdict.stay().evaluation();
        assertThat(stay.value()).isCloseTo(0.855, within(TOLERANCE));
        assertThat(stay.assumptions()).isEmpty();
        assertThat(stay.basis()).isEqualTo(TimeBasis.DIRECT);
        CandidateRecord reboard = recordOf(verdict, "reboard");
        Evaluable evaluable = (Evaluable) reboard.outcome().evaluation();
        assertThat(evaluable.value()).isCloseTo(0.508, within(TOLERANCE));
        assertThat(evaluable.basis()).isEqualTo(TimeBasis.DERIVED);
        assertThat(reboard.outcome().margin().orElseThrow().minutes()).isCloseTo(1.4, within(TOLERANCE));
        assertThat(reboard.outcome().connection()).contains(ConnectionTag.BOUND_UNKNOWN);
        assertThat(recordOf(verdict, "walk-B").admissibility()).isEqualTo(Admissibility.VIOLATED);
        assertThat(recordOf(verdict, "walk-F").admissibility()).isEqualTo(Admissibility.VIOLATED);
    }

    @Test
    void 재탑승_시도_시각을_창_안_차량만_순서대로_낸다() {
        ActionFixture fixture = new ActionFixture();
        Verdict verdict = ActionFixture.judge(fixture.baseline(), config(20, 8), fixture.source(fixture.reboardCandidate()));

        Outcome outcome = recordOf(verdict, "reboard").outcome();
        assertThat(outcome.attempts()).extracting(Attempt::entryId).containsExactly("o-4", "o-9");
        assertThat(outcome.branches()).extracting(branch -> branch.boarded().entryId()).containsExactly("o-4", "o-9");
        assertThat(outcome.branches().get(0).ready().minutes()).isCloseTo(12.6, within(TOLERANCE));
        assertThat(outcome.branches().get(0).reboards()).extracting(Attempt::entryId).containsExactly("b-14", "b-19");
        assertThat(outcome.branches().get(1).ready().minutes()).isCloseTo(17.6, within(TOLERANCE));
        assertThat(outcome.branches().get(1).reboards()).extracting(Attempt::entryId).containsExactly("b-19");
    }

    @Test
    void 창이_15분이면_이득이_역치_미만이라_대기를_유지한다() {
        ActionFixture fixture = new ActionFixture();
        fixture.ride = observed(5);
        Verdict verdict = ActionFixture.judge(fixture.baseline(), config(15, 8), fixture.source(fixture.reboardCandidate()));

        assertThat(verdict.code()).isEqualTo(ReasonCode.STAY_GAIN_BELOW_THRESHOLD);
        assertThat(riskOf(recordOf(verdict, "reboard").outcome())).isCloseTo(0.72, within(TOLERANCE));
        assertThat(verdict.delta().orElseThrow()).isCloseTo(0.135, within(TOLERANCE));
        assertThat(recordOf(verdict, "reboard").outcome().attempts()).extracting(Attempt::entryId).containsExactly("o-4");
        assertThat(recordOf(verdict, "reboard").outcome().branches()).singleElement()
            .satisfies(branch -> assertThat(branch.reboards()).extracting(Attempt::entryId).containsExactly("b-14"));
    }

    @Test
    void 도보_한계로_모든_후보가_위반이면_NO_MOVE_INFEASIBLE이다() {
        ActionFixture fixture = new ActionFixture();
        Verdict verdict = ActionFixture.judge(fixture.baseline(), config(15, 4),
            fixture.source(fixture.walkB(), fixture.walkF(), fixture.reboardCandidate()));

        assertThat(verdict.code()).isEqualTo(ReasonCode.NO_MOVE_INFEASIBLE);
    }

    @Test
    void 도보_시간이_없으면_허용_미확인이라_근거_부족이다() {
        ActionFixture fixture = new ActionFixture();
        fixture.walkAfterAlight = new Missing<>(MissingReason.NOT_PROVIDED);
        Verdict verdict = ActionFixture.judge(fixture.baseline(), config(15, 8), fixture.source(fixture.reboardCandidate()));

        assertThat(verdict.code()).isEqualTo(ReasonCode.ABSTAIN_INSUFFICIENT_EVIDENCE);
        assertThat(recordOf(verdict, "reboard").admissibility()).isEqualTo(Admissibility.UNCONFIRMED);
        assertThat(gapsOf(recordOf(verdict, "reboard").outcome()))
            .containsExactly(new Gap(GapKind.TIME_UNKNOWN, "reboard", Optional.of(MissingReason.NOT_PROVIDED)));
    }

    @Test
    void 우회량이_없으면_허용을_확인하지_못해_근거_부족이다() {
        ActionFixture fixture = new ActionFixture();
        fixture.detour = Optional.empty();
        Verdict verdict = ActionFixture.judge(fixture.baseline(), config(20, 8), fixture.source(fixture.reboardCandidate()));

        assertThat(verdict.code()).isEqualTo(ReasonCode.ABSTAIN_INSUFFICIENT_EVIDENCE);
        assertThat(recordOf(verdict, "reboard").admissibility()).isEqualTo(Admissibility.UNCONFIRMED);
    }

    @Test
    void 우회량이_역치를_넘으면_도보가_허용이어도_위반이다() {
        ActionFixture fixture = new ActionFixture();
        ActionConfig config = config(20, 8);
        fixture.detour = Optional.of(Math.nextUp(config.maxDetour()));

        Verdict verdict = ActionFixture.judge(fixture.baseline(), config, fixture.source(fixture.reboardCandidate()));

        assertThat(recordOf(verdict, "reboard").admissibility()).isEqualTo(Admissibility.VIOLATED);
        assertThat(gapsOf(recordOf(verdict, "reboard").outcome()))
            .containsExactly(new Gap(GapKind.ADMISSIBILITY_VIOLATED, "reboard"));
        assertThat(verdict.code()).isEqualTo(ReasonCode.NO_MOVE_INFEASIBLE);
    }

    @Test
    void 우회량이_역치와_같으면_허용으로_확정된다() {
        ActionFixture fixture = new ActionFixture();
        ActionConfig config = config(20, 8);
        fixture.detour = Optional.of(config.maxDetour());

        Verdict verdict = ActionFixture.judge(fixture.baseline(), config, fixture.source(fixture.reboardCandidate()));

        assertThat(recordOf(verdict, "reboard").admissibility()).isEqualTo(Admissibility.ALLOWED);
        assertThat(verdict.code()).isEqualTo(ReasonCode.GO);
        assertThat(verdict.bestCandidate()).contains("reboard");
    }

    @Test
    void 우회량이_유한한_수가_아니면_후보를_만들지_않는다() {
        ActionFixture fixture = new ActionFixture();
        for (double detour : List.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            fixture.detour = Optional.of(detour);

            assertThatThrownBy(fixture::walkB).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(fixture::reboardCandidate).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void 허용_필터를_거친_뒤에_최선을_고른다() {
        ActionFixture fixture = new ActionFixture();
        WalkCandidate walkB = fixture.walkB();
        Verdict verdict = ActionFixture.judge(fixture.baseline(), config(20, 8),
            fixture.source(walkB, fixture.reboardCandidate()));

        assertThat(verdict.code()).isEqualTo(ReasonCode.GO);
        assertThat(verdict.bestCandidate()).contains("reboard");
    }

    @Test
    void 위반_후보와_입력_부족_후보가_섞이면_NO_MOVE_INFEASIBLE이_아니다() {
        ActionFixture fixture = new ActionFixture();
        fixture.walkAfterAlight = new Missing<>(MissingReason.NOT_PROVIDED);
        Verdict verdict = ActionFixture.judge(fixture.baseline(), config(15, 8),
            fixture.source(fixture.walkB(), fixture.reboardCandidate()));

        assertThat(verdict.code()).isEqualTo(ReasonCode.ABSTAIN_INSUFFICIENT_EVIDENCE);
    }

    @Test
    void 직접_도보는_놓친_차를_시도열에서_빼고_남은_차만_평가한다() {
        ActionFixture fixture = new ActionFixture();
        Verdict verdict = ActionFixture.judge(fixture.baseline(), config(15, 10), fixture.source(fixture.walkB(), fixture.walkF()));

        assertThat(riskOf(recordOf(verdict, "walk-B").outcome())).isCloseTo(0.65, within(TOLERANCE));
        assertThat(riskOf(recordOf(verdict, "walk-F").outcome())).isCloseTo(0.97, within(TOLERANCE));
        assertThat(verdict.code()).isEqualTo(ReasonCode.GO);
        assertThat(verdict.bestCandidate()).contains("walk-B");
        assertThat(verdict.delta().orElseThrow()).isCloseTo(0.205, within(TOLERANCE));
    }

    @Test
    void 준비_시각과_같은_시각의_차와_창_끝의_차를_시도에_넣는다() {
        ActionFixture fixture = new ActionFixture();
        fixture.stay = queried(entry("s0-12", 12), entry("s0-15", 15));
        fixture.stayProbabilities.clear();
        fixture.stayProbabilities.add(marginal("s0-12", 0.2));
        fixture.stayProbabilities.add(ActionFixture.conditional("s0-15", ValueTag.COND_FAIL, 0.5,
            Set.of("s0-12"), Set.of(), Optional.empty()));
        Verdict verdict = ActionFixture.judge(fixture.baseline(), config(15, 8),
            ActionFixture.sourceOf(new Failed(MissingReason.QUERY_FAILED)));

        assertThat(verdict.stay().attempts()).extracting(Attempt::entryId).containsExactly("s0-12", "s0-15");
        assertThat(riskOf(verdict.stay())).isCloseTo(0.4, within(TOLERANCE));
    }

    @Test
    void 직접_도보_준비_시각과_같은_도착은_놓침이_아니다() {
        ActionFixture fixture = new ActionFixture();
        WalkCandidate walk = new WalkCandidate("walk", observed(10), queried(entry("w-12", 12)), Optional.of(0.0));
        fixture.candidateProbabilities.add(marginal("w-12", 0.3));
        Verdict verdict = ActionFixture.judge(fixture.baseline(), config(15, 10), fixture.source(walk));

        assertThat(recordOf(verdict, "walk").outcome().attempts()).extracting(Attempt::entryId).containsExactly("w-12");
    }

    @Test
    void 대기_위험이_탐색_역치와_같으면_후보를_조회한다() {
        ActionFixture fixture = new ActionFixture();
        fixture.stay = queried(entry("s0-3", 3), entry("s0-11", 11));
        fixture.stayProbabilities.clear();
        fixture.stayProbabilities.add(marginal("s0-3", 0.2));
        fixture.stayProbabilities.add(ActionFixture.conditional("s0-11", ValueTag.COND_FAIL, 0.5,
            Set.of("s0-3"), Set.of(), Optional.empty()));
        Verdict verdict = ActionFixture.judge(fixture.baseline(), config(15, 8), ActionFixture.sourceOf(new Failed(MissingReason.QUERY_FAILED)));

        assertThat(verdict.code()).isEqualTo(ReasonCode.ABSTAIN_INSUFFICIENT_EVIDENCE);
    }

    @Test
    void 이득이_역치와_같으면_GO이다() {
        ActionFixture fixture = new ActionFixture();
        WalkCandidate walk = new WalkCandidate("walk", observed(1), queried(entry("w-9", 9)), Optional.of(0.0));
        fixture.candidateProbabilities.add(marginal("w-9", 0.295));
        Verdict verdict = ActionFixture.judge(fixture.baseline(), config(15, 10), fixture.source(walk));

        assertThat(verdict.code()).isEqualTo(ReasonCode.GO);
    }

    @Test
    void 대기_위험이_탐색_역치_미만이면_후보_포트를_부르지_않는다() {
        ActionFixture fixture = new ActionFixture();
        fixture.stayProbabilities.clear();
        fixture.stayProbabilities.add(marginal("s0-3", 0.5));
        fixture.stayProbabilities.add(ActionFixture.conditional("s0-11", ValueTag.COND_FAIL, 0.5,
            Set.of("s0-3"), Set.of(), Optional.empty()));
        Verdict verdict = ActionFixture.judge(fixture.baseline(), config(15, 8), ActionFixture.neverCalled());

        assertThat(verdict.code()).isEqualTo(ReasonCode.STAY_SCAN_NOT_TRIGGERED);
    }

    @Test
    void 후보_열거가_성공했지만_비어_있으면_NO_MOVE_NO_CANDIDATE이다() {
        ActionFixture fixture = new ActionFixture();
        Verdict verdict = ActionFixture.judge(fixture.baseline(), config(15, 8), fixture.source());

        assertThat(verdict.code()).isEqualTo(ReasonCode.NO_MOVE_NO_CANDIDATE);
    }

    @Test
    void 후보_열거가_실패하면_근거_부족이다() {
        ActionFixture fixture = new ActionFixture();
        Verdict verdict = ActionFixture.judge(fixture.baseline(), config(15, 8),
            ActionFixture.sourceOf(new Failed(MissingReason.QUERY_FAILED)));

        assertThat(verdict.code()).isEqualTo(ReasonCode.ABSTAIN_INSUFFICIENT_EVIDENCE);
    }

    @Test
    void 대기_목록_조회가_실패하면_확인된_공집합이_아니라_기준선_미정의이다() {
        ActionFixture fixture = new ActionFixture();
        fixture.stay = new Unavailable(MissingReason.QUERY_FAILED);
        Verdict verdict = ActionFixture.judge(fixture.baseline(), config(15, 8), ActionFixture.neverCalled());

        assertThat(verdict.code()).isEqualTo(ReasonCode.ABSTAIN_STAY_UNDEFINED);
        assertThat(gapsOf(verdict.stay())).containsExactly(
            new Gap(GapKind.LIST_UNAVAILABLE, ListRole.CURRENT, Gap.CURRENT_STOP,
                Optional.of(MissingReason.QUERY_FAILED)));
    }

    @Test
    void 덮임을_확인한_빈_대기_목록은_위험_1의_확인된_공집합이고_탐색을_진행한다() {
        ActionFixture fixture = new ActionFixture();
        fixture.stay = queried();
        Verdict verdict = ActionFixture.judge(fixture.baseline(), config(15, 8), fixture.source());

        assertThat(verdict.stay().evaluation()).isEqualTo(new ConfirmedEmpty(TimeBasis.DIRECT));
        assertThat(verdict.code()).isEqualTo(ReasonCode.NO_MOVE_NO_CANDIDATE);
    }

    @Test
    void 덮임을_모르는_빈_대기_목록은_기준선_미정의이다() {
        ActionFixture fixture = new ActionFixture();
        fixture.stay = new Queried(List.of(), Coverage.COVERAGE_UNKNOWN);
        Verdict verdict = ActionFixture.judge(fixture.baseline(), config(15, 8), ActionFixture.neverCalled());

        assertThat(verdict.code()).isEqualTo(ReasonCode.ABSTAIN_STAY_UNDEFINED);
        assertThat(gapsOf(verdict.stay())).containsExactly(
            new Gap(GapKind.LIST_NOT_COVERING_WINDOW, ListRole.CURRENT, Gap.CURRENT_STOP));
    }

    @Test
    void 복원_항목만_있는_빈_목록은_확인된_공집합이_될_수_없다() {
        ActionFixture fixture = new ActionFixture();
        fixture.stay = new RestoredOnly(List.of());
        Verdict verdict = ActionFixture.judge(fixture.baseline(), config(15, 8), ActionFixture.neverCalled());

        assertThat(verdict.code()).isEqualTo(ReasonCode.ABSTAIN_STAY_UNDEFINED);
    }

    @Test
    void 확률_없이_시각만_있으면_ETA로_확률을_만들지_않고_기준선_미정의이다() {
        ActionFixture fixture = new ActionFixture();
        fixture.stayProbabilities.clear();
        Verdict verdict = ActionFixture.judge(fixture.baseline(), config(15, 8), ActionFixture.neverCalled());

        assertThat(verdict.code()).isEqualTo(ReasonCode.ABSTAIN_STAY_UNDEFINED);
    }

    @Test
    void 앞_인자가_성공_확실이면_뒤_슬롯이_없어도_평가_가능하다() {
        ActionFixture fixture = new ActionFixture();
        fixture.stayProbabilities.clear();
        fixture.stayProbabilities.add(marginal("s0-3", 1.0));
        Verdict verdict = ActionFixture.judge(fixture.baseline(), config(15, 8), ActionFixture.neverCalled());

        assertThat(riskOf(verdict.stay())).isCloseTo(0.0, within(TOLERANCE));
    }

    @Test
    void 주변확률을_조건부_슬롯에_넣으면_독립_가정_표지를_단다() {
        ActionFixture fixture = new ActionFixture();
        fixture.stayProbabilities.set(1, marginal("s0-11", 0.10));
        Verdict verdict = ActionFixture.judge(fixture.baseline(), config(15, 8),
            ActionFixture.sourceOf(new Failed(MissingReason.QUERY_FAILED)));

        Evaluable stay = (Evaluable) verdict.stay().evaluation();
        assertThat(stay.value()).isCloseTo(0.855, within(TOLERANCE));
        assertThat(stay.assumptions()).containsExactly(Assumption.INDEP);
    }

    @Test
    void 좌석_예보_값이_첫_시도_슬롯에_들어가면_가정_없는_결과로_두지_않고_근사_표지를_단다() {
        ActionFixture fixture = new ActionFixture();
        fixture.stay = queried(entry("s0-3", 3));
        fixture.stayProbabilities.clear();
        fixture.stayProbabilities.add(seatForecast("s0-3", 0.7));
        Verdict verdict = ActionFixture.judge(fixture.baseline(), config(15, 8), ActionFixture.neverCalled());

        Evaluable stay = (Evaluable) verdict.stay().evaluation();
        assertThat(stay.value()).isCloseTo(0.3, within(TOLERANCE));
        assertThat(stay.assumptions()).containsExactly(Assumption.SEAT_FORECAST_PROXY);
    }

    @Test
    void 좌석_예보_값으로_두_번_이상_시도하면_근사_표지와_독립_가정_표지를_함께_단다() {
        ActionFixture fixture = new ActionFixture();
        fixture.stayProbabilities.clear();
        fixture.stayProbabilities.add(seatForecast("s0-3", 0.7));
        fixture.stayProbabilities.add(seatForecast("s0-11", 0.4));
        Verdict verdict = ActionFixture.judge(fixture.baseline(), config(15, 8), ActionFixture.neverCalled());

        Evaluable stay = (Evaluable) verdict.stay().evaluation();
        assertThat(stay.value()).isCloseTo(0.18, within(TOLERANCE));
        assertThat(stay.assumptions()).containsExactly(Assumption.INDEP, Assumption.SEAT_FORECAST_PROXY);
    }

    @Test
    void 좌석_예보_값이_하나만_계산에_들어가도_근사_표지를_달고_독립_가정_표지는_달지_않는다() {
        ActionFixture fixture = new ActionFixture();
        fixture.stayProbabilities.set(0, seatForecast("s0-3", 0.05));
        Verdict verdict = ActionFixture.judge(fixture.baseline(), config(15, 8),
            ActionFixture.sourceOf(new Failed(MissingReason.QUERY_FAILED)));

        Evaluable stay = (Evaluable) verdict.stay().evaluation();
        assertThat(stay.value()).isCloseTo(0.855, within(TOLERANCE));
        assertThat(stay.assumptions()).containsExactly(Assumption.SEAT_FORECAST_PROXY);
    }

    @Test
    void 조건_이력의_판정_기가_다르면_일치로_보지_않는다() {
        ActionFixture fixture = new ActionFixture();
        fixture.stayProbabilities.set(1, new ProbabilityInput("s0-11", ValueTag.COND_FAIL,
            Optional.of(new ConditionHistory("epoch-0", Set.of("s0-3"), Set.of(), Optional.empty())), 0.10,
            ValueOrigin.BOARDING));
        Verdict verdict = ActionFixture.judge(fixture.baseline(), config(15, 8), ActionFixture.neverCalled());

        assertThat(verdict.code()).isEqualTo(ReasonCode.ABSTAIN_STAY_UNDEFINED);
    }

    @Test
    void 구분_없는_값은_넣지_않고_결측으로_둔다() {
        ActionFixture fixture = new ActionFixture();
        fixture.stayProbabilities.set(1, new ProbabilityInput("s0-11", ValueTag.UNDIFF, Optional.empty(), 0.10,
            ValueOrigin.BOARDING));
        Verdict verdict = ActionFixture.judge(fixture.baseline(), config(15, 8), ActionFixture.neverCalled());

        assertThat(verdict.code()).isEqualTo(ReasonCode.ABSTAIN_STAY_UNDEFINED);
    }

    @Test
    void 다른_가지의_조건부_값은_다른_가지_슬롯에_재사용하지_않는다() {
        ActionFixture fixture = new ActionFixture();
        fixture.candidateProbabilities.removeIf(input -> input.attempt().equals("b-19")
            && input.tag() == ValueTag.COND_MISS);
        Verdict verdict = ActionFixture.judge(fixture.baseline(), config(20, 8), fixture.source(fixture.reboardCandidate()));

        assertThat(verdict.code()).isEqualTo(ReasonCode.ABSTAIN_INSUFFICIENT_EVIDENCE);
        assertThat(gapsOf(recordOf(verdict, "reboard").outcome()))
            .containsExactly(new Gap(GapKind.PROBABILITY_UNKNOWN, "b-19"));
    }

    @Test
    void 한_가지에_고정된_주변확률을_값이_없는_다른_가지에_쓰지_않는다() {
        ActionFixture fixture = new ActionFixture();
        fixture.reboard = queried(entry("b-19", 19));
        fixture.candidateProbabilities.clear();
        fixture.candidateProbabilities.add(marginal("o-4", 0.5));
        fixture.candidateProbabilities.add(ActionFixture.conditional("o-9", ValueTag.COND_FAIL, 1.0,
            Set.of("o-4"), Set.of(), Optional.empty()));
        fixture.candidateProbabilities.add(ActionFixture.conditional("b-19", ValueTag.MARG, 0.8,
            Set.of(), Set.of(), Optional.of("o-4")));
        Verdict verdict = ActionFixture.judge(fixture.baseline(), config(20, 8), fixture.source(fixture.reboardCandidate()));

        assertThat(gapsOf(recordOf(verdict, "reboard").outcome()))
            .containsExactly(new Gap(GapKind.PROBABILITY_UNKNOWN, "b-19"));
        assertThat(verdict.code()).isEqualTo(ReasonCode.ABSTAIN_INSUFFICIENT_EVIDENCE);
    }

    @Test
    void 확률_입력_순서를_바꿔도_판정이_같다() {
        ProbabilityInput general = marginal("b-19", 0.1);
        ProbabilityInput boardedFirst = ActionFixture.conditional("b-19", ValueTag.MARG, 0.9,
            Set.of(), Set.of(), Optional.of("o-4"));
        ProbabilityInput boardedSecond = ActionFixture.conditional("b-19", ValueTag.MARG, 0.9,
            Set.of(), Set.of(), Optional.of("o-9"));

        Verdict generalFirst = reboardVerdictWith(List.of(general, boardedFirst, boardedSecond));
        Verdict generalLast = reboardVerdictWith(List.of(boardedFirst, boardedSecond, general));

        assertThat(riskOf(recordOf(generalFirst, "reboard").outcome())).isCloseTo(0.136, within(TOLERANCE));
        assertThat(generalFirst.code()).isEqualTo(ReasonCode.GO);
        assertThat(generalLast).isEqualTo(generalFirst);
    }

    private static Verdict reboardVerdictWith(List<ProbabilityInput> reboardProbabilities) {
        ActionFixture fixture = new ActionFixture();
        fixture.reboard = queried(entry("b-19", 19));
        fixture.candidateProbabilities.removeIf(input -> input.attempt().startsWith("b-"));
        fixture.candidateProbabilities.addAll(reboardProbabilities);
        return ActionFixture.judge(fixture.baseline(), config(20, 8), fixture.source(fixture.reboardCandidate()));
    }

    @Test
    void 첫_반대편_확률이_없으면_정책_전체가_미정의이다() {
        ActionFixture fixture = new ActionFixture();
        fixture.ride = observed(5);
        fixture.candidateProbabilities.removeIf(input -> input.attempt().equals("o-4"));
        Verdict verdict = ActionFixture.judge(fixture.baseline(), config(15, 8), fixture.source(fixture.reboardCandidate()));

        assertThat(verdict.code()).isEqualTo(ReasonCode.ABSTAIN_INSUFFICIENT_EVIDENCE);
    }

    @Test
    void 창_밖_가지의_반대편_확률이_없어도_창_안_가지로_평가한다() {
        ActionFixture fixture = new ActionFixture();
        fixture.ride = observed(5);
        fixture.candidateProbabilities.removeIf(input -> input.attempt().equals("o-9"));
        Verdict verdict = ActionFixture.judge(fixture.baseline(), config(15, 8), fixture.source(fixture.reboardCandidate()));

        assertThat(riskOf(recordOf(verdict, "reboard").outcome())).isCloseTo(0.72, within(TOLERANCE));
    }

    @Test
    void 재탑승지_조회가_실패해도_관측_시각만으로_창_밖이_확정되면_확인된_공집합이다() {
        ActionFixture fixture = new ActionFixture();
        fixture.ride = observed(5);
        fixture.reboard = new Unavailable(MissingReason.QUERY_FAILED);
        Verdict verdict = ActionFixture.judge(fixture.baseline(), config(12, 8), fixture.source(fixture.reboardCandidate()));

        assertThat(recordOf(verdict, "reboard").outcome().evaluation()).isInstanceOf(ConfirmedEmpty.class);
        assertThat(verdict.code()).isEqualTo(ReasonCode.STAY_GAIN_BELOW_THRESHOLD);
    }

    @Test
    void 구간시간이_파생이고_상계가_없으면_창_밖을_확정하지_못해_미정의이다() {
        ActionFixture fixture = new ActionFixture();
        fixture.reboard = new Unavailable(MissingReason.QUERY_FAILED);
        Verdict verdict = ActionFixture.judge(fixture.baseline(), config(12, 8), fixture.source(fixture.reboardCandidate()));

        assertThat(gapsOf(recordOf(verdict, "reboard").outcome())).containsExactly(new Gap(GapKind.LIST_UNAVAILABLE,
            ListRole.REBOARD, "reboard", Optional.of(MissingReason.QUERY_FAILED)));
        assertThat(verdict.code()).isEqualTo(ReasonCode.ABSTAIN_INSUFFICIENT_EVIDENCE);
    }

    @Test
    void 경계_가지의_확률_슬롯이_없으면_창_안_시도가_없어도_확인된_공집합으로_보지_않는다() {
        ActionFixture fixture = boundaryFixture();
        Verdict verdict = ActionFixture.judge(fixture.baseline(), config(12, 8), fixture.source(fixture.reboardCandidate()));

        assertThat(gapsOf(recordOf(verdict, "reboard").outcome()))
            .containsExactly(new Gap(GapKind.PROBABILITY_UNKNOWN, "b-11"));
        assertThat(verdict.code()).isEqualTo(ReasonCode.ABSTAIN_INSUFFICIENT_EVIDENCE);
    }

    @Test
    void 경계_가지의_확률_슬롯이_있으면_창_안_시도가_없을_때_확인된_공집합이다() {
        ActionFixture fixture = boundaryFixture();
        fixture.candidateProbabilities.add(ActionFixture.conditional("b-11", ValueTag.COND_MISS, 0.2,
            Set.of(), Set.of("b-6"), Optional.of("o-4")));
        Verdict verdict = ActionFixture.judge(fixture.baseline(), config(12, 8), fixture.source(fixture.reboardCandidate()));

        assertThat(recordOf(verdict, "reboard").outcome().evaluation()).isInstanceOf(ConfirmedEmpty.class);
    }

    @Test
    void 오차가_없으면_해상도로_올림한_t2만으로_경계를_정해_판정할_수_있는_후보를_빼지_않는다() {
        ActionFixture fixture = new ActionFixture();
        fixture.ride = observed(5);
        fixture.reboard = queried(entry("b-6", 6), entry("b-12", 12.7), entry("b-14", 14));
        ActionConfig config = new ActionConfig(0.40, 0.15, new BigDecimal("1.2"), offsetOf(12.8), minutes(8), 100,
            minutes(1), 3, List.of(CandidateKind.WALK, CandidateKind.REBOARD));

        Verdict verdict = ActionFixture.judge(fixture.baseline(), config, fixture.source(fixture.reboardCandidate()));

        assertThat(recordOf(verdict, "reboard").outcome().evaluation()).isInstanceOf(ConfirmedEmpty.class);
    }

    @Test
    void 재탑승_목록이_창을_덮지_않아_판정하지_못하면_그_사유를_남긴다() {
        ActionFixture fixture = new ActionFixture();
        fixture.reboard = new Queried(List.of(entry("b-6", 6)), Coverage.COVERAGE_UNKNOWN);

        Verdict verdict = ActionFixture.judge(fixture.baseline(), config(20, 8), fixture.source(fixture.reboardCandidate()));

        assertThat(gapsOf(recordOf(verdict, "reboard").outcome()))
            .containsExactly(new Gap(GapKind.LIST_NOT_COVERING_WINDOW, ListRole.REBOARD, "reboard"));
    }

    private static ActionFixture boundaryFixture() {
        ActionFixture fixture = new ActionFixture();
        fixture.ride = new Derived<>(minutes(5), new Finite(minutes(2)));
        fixture.reboard = queried(entry("b-6", 6), entry("b-11", 11), entry("b-14", 14), entry("b-19", 19));
        return fixture;
    }

    @Test
    void 분_해상도_양자화_상계는_연결_표지에_반영하지만_시각_근거를_추정으로_바꾸지_않는다() {
        ActionFixture fixture = new ActionFixture();
        StopEntry quantized = new StopEntry("w-12", new Observed<>(Offset.ofMinutes(12.5), Optional.of(minutes(1))));
        StopEntry exact = new StopEntry("w-12", new Observed<>(Offset.ofMinutes(12.5)));
        fixture.candidateProbabilities.add(marginal("w-12", 0.3));
        WalkCandidate withBound = new WalkCandidate("walk", observed(10), queried(quantized), Optional.of(0.0));
        WalkCandidate withoutBound = new WalkCandidate("walk", observed(10), queried(exact), Optional.of(0.0));

        Outcome bounded = recordOf(ActionFixture.judge(fixture.baseline(), config(15, 10), fixture.source(withBound)),
            "walk").outcome();
        Outcome unbounded = recordOf(ActionFixture.judge(fixture.baseline(), config(15, 10),
            fixture.source(withoutBound)), "walk").outcome();

        assertThat(bounded.connection()).contains(ConnectionTag.MARGIN_WITHIN_BOUND);
        assertThat(unbounded.connection()).contains(ConnectionTag.MARGIN_ABOVE_BOUND);
        assertThat(((Evaluable) bounded.evaluation()).basis()).isEqualTo(TimeBasis.DIRECT);
    }

    @Test
    void 상계가_충분히_작으면_보수_하한으로도_창_밖이라_확인된_공집합이다() {
        ActionFixture fixture = new ActionFixture();
        fixture.ride = new Derived<>(minutes(5), new Finite(minutes(0.3)));
        fixture.reboard = new Unavailable(MissingReason.QUERY_FAILED);
        Verdict verdict = ActionFixture.judge(fixture.baseline(), config(12, 8), fixture.source(fixture.reboardCandidate()));

        assertThat(recordOf(verdict, "reboard").outcome().evaluation()).isInstanceOf(ConfirmedEmpty.class);
    }

    @Test
    void 상계가_연결_여유_안이면_오차_범위_안_표지를_단다() {
        ActionFixture fixture = new ActionFixture();
        fixture.ride = new Derived<>(minutes(5), new Finite(minutes(2)));
        Verdict verdict = ActionFixture.judge(fixture.baseline(), config(20, 8), fixture.source(fixture.reboardCandidate()));

        assertThat(verdict.code()).isEqualTo(ReasonCode.GO);
        assertThat(recordOf(verdict, "reboard").outcome().connection()).contains(ConnectionTag.MARGIN_WITHIN_BOUND);
    }

    @Test
    void 같은_점수의_후보는_설정한_종류_순서와_식별자로_고른다() {
        ActionFixture fixture = new ActionFixture();
        fixture.candidateProbabilities.add(marginal("a-9", 0.3));
        fixture.candidateProbabilities.add(marginal("b-9", 0.3));
        WalkCandidate second = new WalkCandidate("walk-b", observed(1), queried(entry("b-9", 9)), Optional.of(0.0));
        WalkCandidate first = new WalkCandidate("walk-a", observed(1), queried(entry("a-9", 9)), Optional.of(0.0));
        Verdict verdict = ActionFixture.judge(fixture.baseline(), config(15, 8), fixture.source(second, first));

        assertThat(verdict.bestCandidate()).contains("walk-a");
        assertThat(verdict.code()).isEqualTo(ReasonCode.GO);
    }

    @Test
    void 비교_가능한_후보가_일부이면_부분_비교_표지를_단다() {
        ActionFixture fixture = new ActionFixture();
        WalkCandidate known = new WalkCandidate("walk-known", observed(1), queried(entry("k-9", 9)), Optional.of(0.0));
        WalkCandidate unknown = new WalkCandidate("walk-unknown", observed(1),
            new Unavailable(MissingReason.QUERY_FAILED), Optional.of(0.0));
        fixture.candidateProbabilities.add(marginal("k-9", 0.5));
        Verdict verdict = ActionFixture.judge(fixture.baseline(), config(15, 8), fixture.source(known, unknown));

        assertThat(verdict.partialComparison()).isTrue();
        assertThat(verdict.bestCandidate()).contains("walk-known");
    }

    @Test
    void 같은_입력은_같은_판정을_낸다() {
        ActionFixture fixture = new ActionFixture();
        ActionJudge.CandidateSource source = fixture.source(fixture.walkB(), fixture.reboardCandidate());
        Verdict first = ActionFixture.judge(fixture.baseline(), config(20, 8), source);
        Verdict second = ActionFixture.judge(fixture.baseline(), config(20, 8), source);

        assertThat(second).isEqualTo(first);
    }

    @Test
    void 재탑승_가지의_탄_차와_시도_순서가_다르면_결과를_만들지_않는다() {
        Attempt first = new Attempt("o-4", offsetOf(4));
        Attempt second = new Attempt("o-9", offsetOf(9));
        ReboardBranch branch = new ReboardBranch(first, offsetOf(12.6), List.of());

        assertThatThrownBy(() -> new Outcome(new ConfirmedEmpty(TimeBasis.DIRECT), List.of(second), List.of(branch),
            Optional.empty(), Optional.empty(), List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 후보를_비교한_판정에_최선_후보나_위험_차이가_비어_있으면_판정을_만들지_않는다() {
        Verdict go = goVerdict();
        for (ReasonCode code : List.of(ReasonCode.GO, ReasonCode.STAY_GAIN_BELOW_THRESHOLD)) {
            assertThatThrownBy(() -> verdictLike(go, code, Optional.empty(), go.delta()))
                .as("%s, bestCandidate 없음", code).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> verdictLike(go, code, go.bestCandidate(), Optional.empty()))
                .as("%s, delta 없음", code).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> verdictLike(go, code, Optional.empty(), Optional.empty()))
                .as("%s, 둘 다 없음", code).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void 후보를_비교하지_않은_판정에_최선_후보나_위험_차이가_있으면_판정을_만들지_않는다() {
        Verdict go = goVerdict();
        for (ReasonCode code : List.of(ReasonCode.ABSTAIN_STAY_UNDEFINED, ReasonCode.STAY_SCAN_NOT_TRIGGERED,
            ReasonCode.NO_MOVE_NO_CANDIDATE, ReasonCode.NO_MOVE_INFEASIBLE, ReasonCode.ABSTAIN_INSUFFICIENT_EVIDENCE)) {
            assertThatThrownBy(() -> verdictLike(go, code, go.bestCandidate(), go.delta()))
                .as("%s, 둘 다 있음", code).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> verdictLike(go, code, go.bestCandidate(), Optional.empty()))
                .as("%s, bestCandidate만 있음", code).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> verdictLike(go, code, Optional.empty(), go.delta()))
                .as("%s, delta만 있음", code).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void 최선_후보가_후보_목록에_없으면_판정을_만들지_않는다() {
        Verdict go = goVerdict();

        assertThatThrownBy(() -> verdictLike(go, ReasonCode.GO, Optional.of("walk-B"), go.delta()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static Verdict goVerdict() {
        ActionFixture fixture = new ActionFixture();
        Verdict verdict = ActionFixture.judge(fixture.baseline(), config(20, 8), fixture.source(fixture.reboardCandidate()));
        assertThat(verdict.code()).isEqualTo(ReasonCode.GO);
        return verdict;
    }

    private static Verdict verdictLike(Verdict base, ReasonCode code, Optional<String> bestCandidate,
                                       Optional<Double> delta) {
        return new Verdict(code, base.epoch(), base.partialComparison(), base.stay(), base.candidates(), bestCandidate,
            delta);
    }

    private static CandidateRecord recordOf(Verdict verdict, String id) {
        return verdict.candidates().stream().filter(record -> record.id().equals(id)).findFirst().orElseThrow();
    }

    private static List<Gap> gapsOf(Outcome outcome) {
        assertThat(outcome.evaluation()).isInstanceOf(Insufficient.class);
        return ((Insufficient) outcome.evaluation()).gaps();
    }

    private static double riskOf(Outcome outcome) {
        return outcome.evaluation().failureRisk().orElseThrow();
    }
}
