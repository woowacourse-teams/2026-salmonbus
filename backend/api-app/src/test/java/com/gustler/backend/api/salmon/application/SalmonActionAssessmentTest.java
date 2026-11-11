package com.gustler.backend.api.salmon.application;

import static org.assertj.core.api.Assertions.assertThat;

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
        Situation situation = adopted().situation(List.of(0.3), List.of(walk(Duration.ofMinutes(10).toMillis())), true);

        assertThat(IntegratedJudge.decide(situation).terminal()).isEqualTo(Terminal.NO_MOVE_POLICY);
    }

    @Test
    void 만차확률_0_8이고_도보가_없으면_평가할_이동이_없다() {
        Situation situation = adopted().situation(List.of(0.8), List.of(), true);

        assertThat(situation.gainThreshold()).isEqualTo(0.5);
        assertThat(situation.eta()).isEqualTo(0);
        assertThat(IntegratedJudge.decide(situation).terminal()).isEqualTo(Terminal.NO_MOVE_NO_CANDIDATE);
    }

    @Test
    void 만차확률이_하나가_아니면_근거_부족이다() {
        assertThat(terminal(List.of())).isEqualTo(Terminal.ABSTAIN_EVIDENCE);
        assertThat(terminal(List.of(0.8, 0.2))).isEqualTo(Terminal.ABSTAIN_EVIDENCE);
        assertThat(terminal(List.of(Double.NaN))).isEqualTo(Terminal.ABSTAIN_EVIDENCE);
        assertThat(terminal(List.of(1.2))).isEqualTo(Terminal.ABSTAIN_EVIDENCE);
    }

    @Test
    void 도보가_15분을_넘으면_허용_범위_밖이다() {
        long over = Duration.ofMinutes(15).toMillis() + 1;

        assertThat(terminal(List.of(0.8), walk(over))).isEqualTo(Terminal.NO_MOVE_INFEASIBLE);
    }

    @Test
    void 도보_15분은_차내_시간이_없어_추천하지_않는다() {
        Recommendation recommendation = IntegratedJudge.decide(
            adopted().situation(List.of(0.8), List.of(walk(Duration.ofMinutes(15).toMillis())), true));

        assertThat(recommendation.terminal()).isEqualTo(Terminal.ABSTAIN_EVIDENCE);
        assertThat(recommendation.chosenId()).isEmpty();
    }

    @Test
    void 도보를_늘려_마감을_넘으면_허용_범위_밖이다() {
        SalmonActionAssessment shortHorizon = assessment(Duration.ofMinutes(30), Duration.ofMinutes(20),
            Duration.ofMinutes(10));

        assertThat(IntegratedJudge.decide(shortHorizon.situation(List.of(0.8),
            List.of(walk(Duration.ofMinutes(10).toMillis())), true)).terminal())
            .isEqualTo(Terminal.NO_MOVE_INFEASIBLE);
        assertThat(IntegratedJudge.decide(shortHorizon.situation(List.of(0.8),
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
        Recommendation recommendation = IntegratedJudge.decide(adopted().situation(List.of(0.8), List.of(planned), true));

        assertThat(recommendation.terminal()).isEqualTo(Terminal.ABSTAIN_EVIDENCE);
        assertThat(recommendation.chosenId()).isEmpty();
    }

    @Test
    void 도보_목록이_잘렸으면_후보_없음으로_덮지_않는다() {
        assertThat(IntegratedJudge.decide(adopted().situation(List.of(0.8), List.of(), false)).terminal())
            .isEqualTo(Terminal.ABSTAIN_EVIDENCE);
    }

    private static Terminal terminal(List<Double> stay) {
        return IntegratedJudge.decide(adopted().situation(stay, List.of(), true)).terminal();
    }

    private static Terminal terminal(List<Double> stay, SalmonActionWalk walk) {
        return IntegratedJudge.decide(adopted().situation(stay, List.of(walk), true)).terminal();
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
