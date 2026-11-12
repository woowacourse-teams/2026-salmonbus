package com.gustler.backend.api.salmon.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.gustler.backend.api.salmon.application.SalmonActionWalk.Connection;
import com.gustler.backend.api.salmon.domain.IntegratedJudge;
import com.gustler.backend.api.salmon.domain.IntegratedJudge.Recommendation;
import com.gustler.backend.api.salmon.domain.IntegratedJudge.Situation;
import com.gustler.backend.api.salmon.domain.IntegratedJudge.Terminal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SalmonActionAssessmentTest {
    @Test
    void 만차확률_0_3이면_도보가_있어도_이동을_찾지_않는다() {
        Situation situation = adopted().situation(List.of(0.3), List.of(), List.of(walk(Duration.ofMinutes(10).toMillis())), true);

        assertThat(IntegratedJudge.decide(situation).terminal()).isEqualTo(Terminal.NO_MOVE_POLICY);
    }

    @Test
    void 만차확률_0_8이고_도보가_없으면_평가할_이동이_없다() {
        Situation situation = adopted().situation(List.of(0.8), List.of(), List.of(), true);

        assertThat(situation.gainThreshold()).isEqualTo(0.5);
        assertThat(situation.eta()).isEqualTo(0);
        assertThat(IntegratedJudge.decide(situation).terminal()).isEqualTo(Terminal.NO_MOVE_NO_CANDIDATE);
    }

    @Test
    void 만차확률이_없거나_쓸_수_없는_값이_섞이면_근거_부족이다() {
        assertThat(terminal(List.of())).isEqualTo(Terminal.ABSTAIN_EVIDENCE);
        assertThat(terminal(List.of(Double.NaN))).isEqualTo(Terminal.ABSTAIN_EVIDENCE);
        assertThat(terminal(List.of(1.2))).isEqualTo(Terminal.ABSTAIN_EVIDENCE);
        assertThat(terminal(List.of(0.2, 1.2))).isEqualTo(Terminal.ABSTAIN_EVIDENCE);
    }

    @Test
    void 만차확률이_둘이면_곱하지_않고_범위로_대기_위험을_만든다() {
        Situation oneLikelyOpen = adopted().situation(List.of(0.8, 0.2), List.of(), List.of(), true);
        Situation bothLikelyFull = adopted().situation(List.of(0.8, 0.9), List.of(), List.of(), true);

        assertThat(oneLikelyOpen.stay().orElseThrow().low()).isEqualTo(0);
        assertThat(oneLikelyOpen.stay().orElseThrow().high()).isEqualTo(0.2);
        assertThat(IntegratedJudge.decide(oneLikelyOpen).terminal()).isEqualTo(Terminal.NO_MOVE_POLICY);
        assertThat(bothLikelyFull.stay().orElseThrow().low()).isCloseTo(0.7, within(1e-9));
        assertThat(bothLikelyFull.stay().orElseThrow().high()).isEqualTo(0.8);
        assertThat(IntegratedJudge.decide(bothLikelyFull).terminal()).isEqualTo(Terminal.NO_MOVE_NO_CANDIDATE);
    }

    @Test
    void 가려진_차량을_넣어야만_역치_아래로_내려가면_옮기지_않아도_된다고_답하지_않는다() {
        Situation hiddenWouldDecide = adopted().situation(List.of(0.8, 0.9), List.of(Optional.of(0.1)), List.of(), true);
        Situation hiddenUnknown = adopted().situation(List.of(0.8, 0.9), List.of(Optional.empty()), List.of(), true);

        assertThat(IntegratedJudge.decide(hiddenWouldDecide).terminal()).isEqualTo(Terminal.ABSTAIN_EVIDENCE);
        assertThat(IntegratedJudge.decide(hiddenUnknown).terminal()).isEqualTo(Terminal.ABSTAIN_EVIDENCE);
    }

    @Test
    void 도착_목록의_차량만으로_역치_아래면_가려진_차량과_무관하게_옮기지_않아도_된다() {
        Situation listedDecides = adopted().situation(List.of(0.8, 0.2), List.of(Optional.of(0.9)), List.of(), true);
        Situation hiddenUnknown = adopted().situation(List.of(0.8, 0.2), List.of(Optional.empty()), List.of(), true);

        assertThat(IntegratedJudge.decide(listedDecides).terminal()).isEqualTo(Terminal.NO_MOVE_POLICY);
        assertThat(IntegratedJudge.decide(hiddenUnknown).terminal()).isEqualTo(Terminal.NO_MOVE_POLICY);
        assertThat(listedDecides.stay().orElseThrow().high()).isEqualTo(0.2);
    }

    @Test
    void 가려진_차량의_만차확률도_높으면_상한은_그대로_두고_하한만_낮춘다() {
        Situation everyBusLikelyFull = adopted().situation(List.of(0.8, 0.9), List.of(Optional.of(0.7)), List.of(), true);

        assertThat(everyBusLikelyFull.stay().orElseThrow().low()).isCloseTo(0.4, within(1e-9));
        assertThat(everyBusLikelyFull.stay().orElseThrow().high()).isEqualTo(0.8);
        assertThat(IntegratedJudge.decide(everyBusLikelyFull).terminal()).isEqualTo(Terminal.NO_MOVE_NO_CANDIDATE);
    }

    @Test
    void 도보가_15분을_넘으면_허용_범위_밖이다() {
        long over = Duration.ofMinutes(15).toMillis() + 1;

        assertThat(terminal(List.of(0.8), walk(over))).isEqualTo(Terminal.NO_MOVE_INFEASIBLE);
    }

    @Test
    void 도보_15분은_차내_시간이_없어_추천하지_않는다() {
        Recommendation recommendation = IntegratedJudge.decide(
            adopted().situation(List.of(0.8), List.of(), List.of(walk(Duration.ofMinutes(15).toMillis())), true));

        assertThat(recommendation.terminal()).isEqualTo(Terminal.ABSTAIN_EVIDENCE);
        assertThat(recommendation.chosenId()).isEmpty();
    }

    @Test
    void 도보를_늘려_마감을_넘으면_허용_범위_밖이다() {
        SalmonActionAssessment shortHorizon = assessment(Duration.ofMinutes(30), Duration.ofMinutes(20),
            Duration.ofMinutes(10));

        assertThat(IntegratedJudge.decide(shortHorizon.situation(List.of(0.8), List.of(),
            List.of(walk(Duration.ofMinutes(10).toMillis())), true)).terminal())
            .isEqualTo(Terminal.NO_MOVE_INFEASIBLE);
        assertThat(IntegratedJudge.decide(shortHorizon.situation(List.of(0.8), List.of(),
            List.of(walk(Duration.ofMinutes(8).toMillis())), true)).terminal())
            .isEqualTo(Terminal.ABSTAIN_EVIDENCE);
    }

    @Test
    void 환승이_한_번을_넘으면_허용_범위_밖이다() {
        assertThat(terminal(List.of(0.8), walk(Duration.ofMinutes(5).toMillis(), 2, Optional.empty(), Optional.empty())))
            .isEqualTo(Terminal.NO_MOVE_INFEASIBLE);
        assertThat(terminal(List.of(0.8), walk(Duration.ofMinutes(5).toMillis(), 1, Optional.empty(), Optional.empty())))
            .isEqualTo(Terminal.ABSTAIN_EVIDENCE);
    }

    @Test
    void 차내_시간이_20분을_넘으면_허용_범위_밖이다() {
        Optional<Long> over = Optional.of(Duration.ofMinutes(20).toMillis() + 1);

        assertThat(terminal(List.of(0.8), walk(Duration.ofMinutes(5).toMillis(), 0, over, Optional.empty())))
            .isEqualTo(Terminal.NO_MOVE_INFEASIBLE);
    }

    @Test
    void 접속_여유는_15초부터_남긴다() {
        SalmonActionWalk missed = walk(Duration.ofMinutes(5).toMillis(), 0, Optional.empty(),
            Optional.of(new Connection(14_000, 0)));
        SalmonActionWalk met = walk(Duration.ofMinutes(5).toMillis(), 0, Optional.empty(),
            Optional.of(new Connection(15_000, 0)));

        assertThat(terminal(List.of(0.8), missed)).isEqualTo(Terminal.NO_MOVE_INFEASIBLE);
        assertThat(terminal(List.of(0.8), met)).isEqualTo(Terminal.ABSTAIN_EVIDENCE);
    }

    @Test
    void 차내_시간이_있어도_이동_확률이_없으면_추천하지_않는다() {
        SalmonActionWalk planned = walk(Duration.ofMinutes(5).toMillis(), 0,
            Optional.of(Duration.ofMinutes(20).toMillis()), Optional.empty());
        Recommendation recommendation = IntegratedJudge.decide(adopted().situation(List.of(0.8), List.of(), List.of(planned), true));

        assertThat(recommendation.terminal()).isEqualTo(Terminal.ABSTAIN_EVIDENCE);
        assertThat(recommendation.chosenId()).isEmpty();
    }

    @Test
    void 도보_목록이_잘렸으면_후보_없음으로_덮지_않는다() {
        assertThat(IntegratedJudge.decide(adopted().situation(List.of(0.8), List.of(), List.of(), false)).terminal())
            .isEqualTo(Terminal.ABSTAIN_EVIDENCE);
    }

    private static Terminal terminal(List<Double> stay) {
        return IntegratedJudge.decide(adopted().situation(stay, List.of(), List.of(), true)).terminal();
    }

    private static Terminal terminal(List<Double> stay, SalmonActionWalk walk) {
        return IntegratedJudge.decide(adopted().situation(stay, List.of(), List.of(walk), true)).terminal();
    }

    private static SalmonActionWalk walk(long walkMillis) {
        return walk(walkMillis, 0, Optional.empty(), Optional.empty());
    }

    private static SalmonActionWalk walk(
        long walkMillis,
        int transfers,
        Optional<Long> inVehicleMillis,
        Optional<Connection> connection
    ) {
        return new SalmonActionWalk("205000100:3", walkMillis, transfers, inVehicleMillis, connection);
    }

    private static SalmonActionAssessment adopted() {
        return new SalmonActionAssessment(SalmonSettingsFixture.adopted());
    }

    private static SalmonActionAssessment assessment(Duration walkCap, Duration inVehicleCap, Duration horizon) {
        return new SalmonActionAssessment(SalmonSettingsFixture.with(walkCap, inVehicleCap, horizon));
    }
}
