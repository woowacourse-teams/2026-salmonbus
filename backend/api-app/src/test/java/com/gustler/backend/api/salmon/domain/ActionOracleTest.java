package com.gustler.backend.api.salmon.domain;

import static com.gustler.backend.api.salmon.domain.ActionFixture.config;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.gustler.backend.api.salmon.domain.ActionModel.*;
import com.gustler.backend.api.salmon.domain.LeafOracle.Leaf;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ActionOracleTest {
    private static final double TOLERANCE = 1e-9;
    private static final double[] GRID = {0.0, 0.35, 1.0};

    @Test
    void 오라클은_가상_픽스처의_잎_표를_재현한다() {
        List<Leaf> leaves = LeafOracle.reboardLeaves(new double[] {0.8, 0.8}, new double[][] {{0.35, 0.30}, {0.35}});

        assertThat(leaves).extracting(Leaf::probability).usingElementComparator((a, b) -> Math.abs(a - b) < TOLERANCE ? 0 : 1)
            .containsExactly(0.280, 0.156, 0.364, 0.056, 0.104, 0.040);
        assertThat(LeafOracle.failure(leaves)).isCloseTo(0.508, within(TOLERANCE));
    }

    @Test
    void 재탑승_위험은_확률_조합_전체에서_잎_합산과_일치한다() {
        for (double beta1 : GRID) {
            for (double beta2 : GRID) {
                for (double q14 : GRID) {
                    for (double q19First : GRID) {
                        for (double q19Second : GRID) {
                            ActionFixture fixture = fixtureWith(beta1, beta2, q14, q19First, q19Second);
                            fixture.ride = ActionFixture.observed(5);
                            Verdict verdict = ActionJudge.judge(fixture.baseline(), config(20, 8),
                                fixture.source(fixture.reboardCandidate()));
                            double expected = LeafOracle.failure(LeafOracle.reboardLeaves(
                                new double[] {beta1, beta2}, new double[][] {{q14, q19First}, {q19Second}}));
                            double actual = verdict.candidates().get(0).outcome().evaluation().failureRisk().orElseThrow();
                            assertThat(actual).describedAs("%s %s %s %s %s", beta1, beta2, q14, q19First, q19Second)
                                .isCloseTo(expected, within(TOLERANCE));
                        }
                    }
                }
            }
        }
    }

    @Test
    void 대기_위험은_확률_조합_전체에서_잎_합산과_일치한다() {
        for (double first : GRID) {
            for (double second : GRID) {
                ActionFixture fixture = new ActionFixture();
                fixture.stayProbabilities.set(0, ActionFixture.marginal("s0-3", first));
                fixture.stayProbabilities.set(1, ActionFixture.conditional("s0-11", ValueTag.COND_FAIL, second,
                    Set.of("s0-3"), Set.of(), Optional.empty()));
                Verdict verdict = ActionJudge.judge(fixture.baseline(), config(15, 8), ActionFixture.sourceOf(
                    new ActionJudge.Listed(List.of(), ProbabilitySupply.none(), Optional.empty())));
                double expected = LeafOracle.failure(LeafOracle.attemptLeaves(new double[] {first, second}));
                assertThat(verdict.stay().evaluation().failureRisk().orElseThrow()).isCloseTo(expected, within(TOLERANCE));
            }
        }
    }

    @Test
    void 창을_15에서_20으로_넓히면_모든_시도열이_다시_잘려_코드가_바뀐다() {
        ActionFixture narrow = new ActionFixture();
        narrow.ride = ActionFixture.observed(5);
        Verdict narrowVerdict = ActionJudge.judge(narrow.baseline(), config(15, 8), narrow.source(narrow.reboardCandidate()));
        ActionFixture wide = new ActionFixture();
        wide.ride = ActionFixture.observed(5);
        Verdict wideVerdict = ActionJudge.judge(wide.baseline(), config(20, 8), wide.source(wide.reboardCandidate()));

        assertThat(narrowVerdict.code()).isEqualTo(ReasonCode.STAY_GAIN_BELOW_THRESHOLD);
        assertThat(wideVerdict.code()).isEqualTo(ReasonCode.GO);
        assertThat(LeafOracle.failure(LeafOracle.reboardLeaves(new double[] {0.8}, new double[][] {{0.35}})))
            .isCloseTo(0.72, within(TOLERANCE));
    }

    private static ActionFixture fixtureWith(double beta1, double beta2, double q14, double q19First, double q19Second) {
        ActionFixture fixture = new ActionFixture();
        fixture.candidateProbabilities.removeIf(input -> input.attempt().startsWith("o-") || input.attempt().startsWith("b-"));
        fixture.candidateProbabilities.add(ActionFixture.marginal("o-4", beta1));
        fixture.candidateProbabilities.add(ActionFixture.conditional("o-9", ValueTag.COND_FAIL, beta2,
            Set.of("o-4"), Set.of(), Optional.empty()));
        fixture.candidateProbabilities.add(ActionFixture.conditional("b-14", ValueTag.COND_MISS, q14,
            Set.of(), Set.of("b-6"), Optional.of("o-4")));
        fixture.candidateProbabilities.add(ActionFixture.conditional("b-19", ValueTag.COND_FAIL, q19First,
            Set.of("b-14"), Set.of("b-6"), Optional.of("o-4")));
        fixture.candidateProbabilities.add(ActionFixture.conditional("b-19", ValueTag.COND_MISS, q19Second,
            Set.of(), Set.of("b-6", "b-14"), Optional.of("o-9")));
        return fixture;
    }
}
