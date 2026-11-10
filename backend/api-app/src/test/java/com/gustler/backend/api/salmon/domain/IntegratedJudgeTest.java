package com.gustler.backend.api.salmon.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.gustler.backend.api.salmon.domain.IntegratedJudge.Admission;
import com.gustler.backend.api.salmon.domain.IntegratedJudge.Coverage;
import com.gustler.backend.api.salmon.domain.IntegratedJudge.Listing;
import com.gustler.backend.api.salmon.domain.IntegratedJudge.Move;
import com.gustler.backend.api.salmon.domain.IntegratedJudge.Recommendation;
import com.gustler.backend.api.salmon.domain.IntegratedJudge.Situation;
import com.gustler.backend.api.salmon.domain.IntegratedJudge.Terminal;
import com.gustler.backend.api.salmon.domain.RiskMath.Bound;
import com.gustler.backend.api.salmon.domain.RiskMath.Chance;
import com.gustler.backend.api.salmon.domain.RiskMath.Risk;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IntegratedJudgeTest {
    private static final double TOLERANCE = 1e-9;

    @Test
    void 명세_12절_범위는_재탑승을_GO로_고른다() {
        Risk stay = RiskMath.directFailure(List.of(new Chance(0.08, 0.12), new Chance(0.10, 0.14)));
        Risk reboard = RiskMath.reboardFailure(new Chance(0.88, 0.92), new Chance(0.78, 0.82));
        assertThat(stay.low()).isCloseTo(0.7568, within(TOLERANCE));
        assertThat(stay.high()).isCloseTo(0.828, within(TOLERANCE));
        assertThat(reboard.low()).isCloseTo(0.2456, within(TOLERANCE));
        assertThat(reboard.high()).isCloseTo(0.3136, within(TOLERANCE));

        Recommendation recommendation = IntegratedJudge.decide(open(stay, Coverage.MODEL_WINDOW, false, Listing.COMPLETE,
            allowed("walk-beomgye", new Risk(1, 1), 16, 0, 0),
            allowed("reboard-v", reboard, 4, 1, 15)));

        assertThat(recommendation.terminal()).isEqualTo(Terminal.GO);
        assertThat(recommendation.chosenId()).contains("reboard-v");
        assertThat(recommendation.partial()).isFalse();
        Bound gain = recommendation.delta().orElseThrow();
        assertThat(gain.low()).isCloseTo(0.4432, within(TOLERANCE));
        assertThat(gain.high()).isCloseTo(0.5824, within(TOLERANCE));
    }

    @Test
    void 명세_12절_점값은_대기_0점792와_재탑승_0점28이다() {
        Risk stay = RiskMath.directFailure(List.of(new Chance(0.10, 0.10), new Chance(0.12, 0.12)));
        Risk reboard = RiskMath.reboardFailure(new Chance(0.90, 0.90), new Chance(0.80, 0.80));
        Bound gain = RiskMath.delta(stay, reboard);

        assertThat(stay.low()).isCloseTo(0.792, within(TOLERANCE));
        assertThat(reboard.low()).isCloseTo(0.28, within(TOLERANCE));
        assertThat(gain.low()).isCloseTo(0.512, within(TOLERANCE));
        assertThat(IntegratedJudge.decide(open(stay, Coverage.MODEL_WINDOW, false, Listing.COMPLETE,
            allowed("reboard-v", reboard, 4, 1, 15))).terminal()).isEqualTo(Terminal.GO);
    }

    @Test
    void 주변확률_두_개의_실패_범위는_합_제약이다() {
        Risk bound = RiskMath.marginalPair(0.10, 0.12);

        assertThat(bound.low()).isCloseTo(0.78, within(TOLERANCE));
        assertThat(bound.high()).isCloseTo(0.88, within(TOLERANCE));
        assertThatThrownBy(() -> RiskMath.marginalPair(1.2, 0.1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 직접_시도가_없으면_실패_위험은_1이다() {
        assertThat(RiskMath.directFailure(List.of())).isEqualTo(new Risk(1, 1));
    }

    @Test
    void 역치가_범위를_벗어나면_입력_오류다() {
        assertThat(IntegratedJudge.decide(threshold(0)).terminal()).isEqualTo(Terminal.INVALID_INPUT);
        assertThat(IntegratedJudge.decide(threshold(1.1)).terminal()).isEqualTo(Terminal.INVALID_INPUT);
        assertThat(IntegratedJudge.decide(new Situation(true, true, Optional.of(new Risk(0.5, 0.5)),
            Coverage.MODEL_WINDOW, false, Listing.COMPLETE, 0.15, -0.01, List.of())).terminal())
            .isEqualTo(Terminal.INVALID_INPUT);
        assertThat(IntegratedJudge.decide(Situation.contradiction()).terminal()).isEqualTo(Terminal.INVALID_INPUT);
    }

    @Test
    void 허용인데_차내_이동이_없으면_입력_오류다() {
        Move walk = new Move("walk", Admission.ALLOWED, Optional.of(new Risk(0.2, 0.2)), true, 4, 0, Optional.empty());

        assertThat(IntegratedJudge.decide(open(new Risk(0.8, 0.8), Coverage.MODEL_WINDOW, false, Listing.COMPLETE, walk))
            .terminal()).isEqualTo(Terminal.INVALID_INPUT);
    }

    @Test
    void 마감이_지나면_창_만료다() {
        Situation expired = new Situation(true, false, Optional.of(new Risk(0.9, 0.9)), Coverage.MODEL_WINDOW, false,
            Listing.COMPLETE, 0.15, 0, List.of());

        assertThat(IntegratedJudge.decide(expired).terminal()).isEqualTo(Terminal.WINDOW_EXPIRED);
    }

    @Test
    void 대기가_없으면_근거_부족이다() {
        assertThat(IntegratedJudge.decide(Situation.missingStay()).terminal()).isEqualTo(Terminal.ABSTAIN_EVIDENCE);
    }

    @Test
    void 대기_상한이_역치보다_낮으면_탐색을_생략한다() {
        Move better = allowed("reboard", new Risk(0.01, 0.02), 4, 1, 10);

        assertThat(IntegratedJudge.decide(open(new Risk(0.05, 0.10), Coverage.MODEL_WINDOW, false, Listing.COMPLETE,
            better)).terminal()).isEqualTo(Terminal.NO_MOVE_POLICY);
    }

    @Test
    void 대기_상한이_역치와_같으면_후보_없음을_본다() {
        assertThat(IntegratedJudge.decide(open(new Risk(0.15, 0.15), Coverage.MODEL_WINDOW, false, Listing.COMPLETE))
            .terminal()).isEqualTo(Terminal.NO_MOVE_NO_CANDIDATE);
    }

    @Test
    void 열거가_끝나지_않으면_후보_없음으로_덮지_않는다() {
        assertThat(IntegratedJudge.decide(open(new Risk(0.5, 0.5), Coverage.MODEL_WINDOW, false, Listing.UNFINISHED))
            .terminal()).isEqualTo(Terminal.ABSTAIN_EVIDENCE);
    }

    @Test
    void 끝난_열거에_후보가_없으면_후보_없음이다() {
        assertThat(IntegratedJudge.decide(open(new Risk(0.5, 0.5), Coverage.MODEL_WINDOW, false, Listing.COMPLETE))
            .terminal()).isEqualTo(Terminal.NO_MOVE_NO_CANDIDATE);
    }

    @Test
    void 모든_후보가_위반이면_갈_수_없다() {
        Move walk = new Move("walk", Admission.VIOLATED, Optional.empty(), true, 30, 0, Optional.empty());

        assertThat(IntegratedJudge.decide(open(new Risk(0.8, 0.8), Coverage.MODEL_WINDOW, false, Listing.COMPLETE, walk))
            .terminal()).isEqualTo(Terminal.NO_MOVE_INFEASIBLE);
    }

    @Test
    void 제한된_관측에서_숫자만_넘으면_추천하지_않는다() {
        Recommendation recommendation = IntegratedJudge.decide(open(new Risk(0.8, 0.9), Coverage.RESTRICTED, false,
            Listing.COMPLETE, allowed("reboard", new Risk(0.1, 0.2), 4, 1, 10)));

        assertThat(recommendation.terminal()).isEqualTo(Terminal.ABSTAIN_EVIDENCE);
    }

    @Test
    void 누락을_넣은_보수_범위가_역치를_넘으면_GO다() {
        Recommendation recommendation = IntegratedJudge.decide(open(new Risk(0.8, 0.9), Coverage.RESTRICTED, true,
            Listing.COMPLETE, allowed("reboard", new Risk(0.1, 0.2), 4, 1, 10)));

        assertThat(recommendation.terminal()).isEqualTo(Terminal.GO);
        assertThat(recommendation.chosenId()).contains("reboard");
    }

    @Test
    void 이득_범위가_역치를_가로지르면_이득_불확실이다() {
        assertThat(IntegratedJudge.decide(open(new Risk(0.40, 0.50), Coverage.MODEL_WINDOW, false, Listing.COMPLETE,
            allowed("reboard", new Risk(0.20, 0.40), 4, 1, 10))).terminal())
            .isEqualTo(Terminal.ABSTAIN_UNCERTAIN_GAIN);
    }

    @Test
    void 점추정만_넘으면_이득_불확실이다() {
        Move point = new Move("reboard", Admission.ALLOWED, Optional.of(new Risk(0.2, 0.2)), false, 4, 1,
            Optional.of(10.0));

        assertThat(IntegratedJudge.decide(open(new Risk(0.8, 0.8), Coverage.MODEL_WINDOW, false, Listing.COMPLETE, point))
            .terminal()).isEqualTo(Terminal.ABSTAIN_UNCERTAIN_GAIN);
    }

    @Test
    void 비교한_허용_후보의_이득_상한이_모두_역치_미만이면_이득_없음이다() {
        assertThat(IntegratedJudge.decide(open(new Risk(0.20, 0.30), Coverage.MODEL_WINDOW, false, Listing.COMPLETE,
            allowed("reboard", new Risk(0.20, 0.25), 4, 1, 10))).terminal())
            .isEqualTo(Terminal.NO_MOVE_NO_GAIN);
    }

    @Test
    void 미확인_후보가_이득_가능성을_남기면_이득_없음으로_덮지_않는다() {
        Move confirmed = allowed("reboard", new Risk(0.20, 0.25), 4, 1, 10);
        Move unresolved = new Move("walk", Admission.UNCONFIRMED, Optional.empty(), true, 8, 0, Optional.empty());

        assertThat(IntegratedJudge.decide(open(new Risk(0.20, 0.30), Coverage.MODEL_WINDOW, false, Listing.COMPLETE,
            confirmed, unresolved)).terminal()).isEqualTo(Terminal.ABSTAIN_EVIDENCE);
    }

    @Test
    void 확인된_GO가_있으면_열거가_덜_끝나도_그_후보를_고른다() {
        Recommendation recommendation = IntegratedJudge.decide(open(new Risk(0.8, 0.9), Coverage.MODEL_WINDOW, false,
            Listing.UNFINISHED, allowed("reboard", new Risk(0.1, 0.2), 4, 1, 10)));

        assertThat(recommendation.terminal()).isEqualTo(Terminal.GO);
        assertThat(recommendation.partial()).isTrue();
    }

    @Test
    void 같은_위험_상한에서는_도보가_짧은_후보를_고른다() {
        Move longer = allowed("long", new Risk(0.20, 0.20), 12, 1, 10);
        Move shorter = allowed("short", new Risk(0.22, 0.22), 4, 1, 15);
        Situation near = new Situation(true, true, Optional.of(new Risk(0.90, 0.90)), Coverage.MODEL_WINDOW, false,
            Listing.COMPLETE, 0.15, 0.05, List.of(longer, shorter));
        Situation exact = new Situation(true, true, Optional.of(new Risk(0.90, 0.90)), Coverage.MODEL_WINDOW, false,
            Listing.COMPLETE, 0.15, 0, List.of(longer, shorter));

        assertThat(IntegratedJudge.decide(near).chosenId()).contains("short");
        assertThat(IntegratedJudge.decide(exact).chosenId()).contains("long");
    }

    private static Situation open(Risk stay, Coverage coverage, boolean omissionsIncluded, Listing listing, Move... moves) {
        return new Situation(true, true, Optional.of(stay), coverage, omissionsIncluded, listing, 0.15, 0, List.of(moves));
    }

    private static Situation threshold(double gainThreshold) {
        return new Situation(true, true, Optional.of(new Risk(0.5, 0.5)), Coverage.MODEL_WINDOW, false, Listing.COMPLETE,
            gainThreshold, 0, List.of());
    }

    private static Move allowed(String id, Risk risk, double walkMinutes, int transfers, double inVehicleMinutes) {
        return new Move(id, Admission.ALLOWED, Optional.of(risk), true, walkMinutes, transfers,
            Optional.of(inVehicleMinutes));
    }
}
