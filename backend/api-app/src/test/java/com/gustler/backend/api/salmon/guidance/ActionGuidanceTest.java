package com.gustler.backend.api.salmon.guidance;

import static com.gustler.backend.api.salmon.domain.ActionFixture.config;
import static com.gustler.backend.api.salmon.domain.ActionFixture.entry;
import static com.gustler.backend.api.salmon.domain.ActionFixture.marginal;
import static com.gustler.backend.api.salmon.domain.ActionFixture.observed;
import static com.gustler.backend.api.salmon.domain.ActionFixture.queried;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.gustler.backend.api.salmon.domain.ActionConfig;
import com.gustler.backend.api.salmon.domain.ActionFixture;
import com.gustler.backend.api.salmon.domain.ActionJudge;
import com.gustler.backend.api.salmon.domain.Candidates.*;
import com.gustler.backend.api.salmon.domain.Fields.*;
import com.gustler.backend.api.salmon.domain.Probabilities.*;
import com.gustler.backend.api.salmon.domain.StopLists.*;
import com.gustler.backend.api.salmon.domain.Verdicts.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ActionGuidanceTest {
    private static final String LONG_DASH = new String(Character.toChars(0x2014));
    private static final List<String> FORBIDDEN_PHRASES = List.of("확실히 탈 수 있", "가장 좋", "기다리는 편이 낫",
        "이동할 필요가 없", "곧 온", "기다리세요", "실제로 차가 없", "모든 정류장을 비교", "여기가 가장",
        "탑승이 어렵", "탑승이 쉽", "판교에 도착", "전체 최선", "일부러 보내");
    private static final String MIDDLE_DOT = new String(Character.toChars(0x00B7));

    @Test
    void 재탑승_GO는_맞은편_승차부터_재탑승까지_순서와_시도_시각을_보인다() {
        ActionFixture fixture = new ActionFixture();
        ActionConfig config = config(20, 8);
        Verdict verdict = ActionJudge.judge(fixture.baseline(), config, fixture.source(fixture.reboardCandidate()));

        String text = String.join("\n", ActionGuidance.compose(verdict, config));
        assertThat(text).contains("맞은편 정류장으로 이동해요", "약 4분 뒤 차를 시도해요", "약 9분 뒤 차를 시도할 수 있어요",
            "정해진 하차 정류장에서 내려요", "길을 건너", "맞은편에서 약 4분 뒤 차를 탔다면 지금부터 약 14분 뒤 차를 시도해요", "약 19분 뒤 차를 시도할 수 있어요",
            "입력된 차량 기준", "구간시간은 추정", "연결 여유는 약 1.4분", "근거가 부족해요");
        assertThat(text).doesNotContain("확실히");
    }

    @Test
    void 맞은편_둘째_차를_탄_가지는_그_가지의_재탑승_시각을_지금부터_기준으로_보인다() {
        ActionFixture fixture = new ActionFixture();
        ActionConfig config = config(20, 8);
        Verdict verdict = ActionJudge.judge(fixture.baseline(), config, fixture.source(fixture.reboardCandidate()));

        assertThat(verdict.code()).isEqualTo(ReasonCode.GO);
        List<String> lines = ActionGuidance.compose(verdict, config);
        assertAllowed(String.join("\n", lines));
        assertThat(lines).filteredOn(line -> line.startsWith("맞은편에서 약 9분 뒤"))
            .containsExactly("맞은편에서 약 9분 뒤 차를 탔다면 지금부터 약 19분 뒤 차를 시도해요.");
    }

    @Test
    void 하차_뒤_준비_시각보다_먼저_오는_재탑승_차는_그_가지_문장에_넣지_않는다() {
        ActionFixture fixture = new ActionFixture();
        ActionConfig config = config(20, 8);
        Verdict verdict = ActionJudge.judge(fixture.baseline(), config, fixture.source(fixture.reboardCandidate()));

        assertThat(verdict.code()).isEqualTo(ReasonCode.GO);
        List<String> lines = ActionGuidance.compose(verdict, config);
        assertAllowed(String.join("\n", lines));
        assertThat(lines).filteredOn(line -> line.startsWith("맞은편에서 약 9분 뒤"))
            .singleElement().asString().doesNotContain("14분");
        assertThat(lines).filteredOn(line -> line.startsWith("맞은편에서 약 4분 뒤"))
            .containsExactly("맞은편에서 약 4분 뒤 차를 탔다면 지금부터 약 14분 뒤 차를 시도해요. "
                + "못 타면 지금부터 약 19분 뒤 차를 시도할 수 있어요.");
        assertThat(String.join("\n", lines)).doesNotContain("약 6분 뒤 차");
    }

    @Test
    void 창_안에_재탑승할_차가_없는_가지는_시도할_차가_없다고_밝힌다() {
        ActionFixture fixture = new ActionFixture();
        fixture.reboard = queried(entry("b-6", 6), entry("b-14", 14));
        fixture.candidateProbabilities.removeIf(input -> input.attempt().equals("b-14"));
        fixture.candidateProbabilities.add(ActionFixture.conditional("b-14", ValueTag.COND_MISS, 0.5,
            Set.of(), Set.of("b-6"), Optional.of("o-4")));
        ActionConfig config = config(20, 8);
        Verdict verdict = ActionJudge.judge(fixture.baseline(), config, fixture.source(fixture.reboardCandidate()));

        assertThat(verdict.code()).isEqualTo(ReasonCode.GO);
        assertThat(verdict.candidates().get(0).outcome().branches().get(1).reboards()).isEmpty();
        List<String> lines = ActionGuidance.compose(verdict, config);
        assertAllowed(String.join("\n", lines));
        assertThat(lines).filteredOn(line -> line.startsWith("맞은편에서 약 9분 뒤"))
            .containsExactly("맞은편에서 약 9분 뒤 차를 탔다면 입력된 차량 기준으로 지금부터 20분 안에 "
                + "원하는 방향 정류장에서 시도할 차가 없어요.")
            .allSatisfy(line -> assertThat(line).doesNotContain("14분"));
    }

    @Test
    void 맞은편_승차_확률이_0인_가지도_평가한_시도_순서대로_문장을_보인다() {
        ActionFixture fixture = new ActionFixture();
        fixture.candidateProbabilities.set(1, ActionFixture.conditional("o-9", ValueTag.COND_FAIL, 0.0,
            Set.of("o-4"), Set.of(), Optional.empty()));
        ActionConfig config = config(20, 8);
        Verdict verdict = ActionJudge.judge(fixture.baseline(), config, fixture.source(fixture.reboardCandidate()));

        assertThat(verdict.code()).isEqualTo(ReasonCode.GO);
        List<String> lines = ActionGuidance.compose(verdict, config);
        assertAllowed(String.join("\n", lines));
        assertThat(lines).filteredOn(line -> line.startsWith("맞은편에서 약 9분 뒤"))
            .containsExactly("맞은편에서 약 9분 뒤 차를 탔다면 지금부터 약 19분 뒤 차를 시도해요.");
        assertThat(verdict.candidates().get(0).outcome().margin().orElseThrow().minutes())
            .isCloseTo(1.4, within(1e-9));
    }

    @Test
    void 가지_문장은_맞은편_시도_문장에_나온_차까지만_보인다() {
        ActionFixture fixture = new ActionFixture();
        fixture.opposite = queried(entry("o-4", 4), entry("o-9", 9), entry("o-10", 10));
        fixture.candidateProbabilities.add(ActionFixture.conditional("o-10", ValueTag.COND_FAIL, 0.8,
            Set.of("o-4", "o-9"), Set.of(), Optional.empty()));
        fixture.candidateProbabilities.add(ActionFixture.conditional("b-19", ValueTag.COND_MISS, 0.35,
            Set.of(), Set.of("b-6", "b-14"), Optional.of("o-10")));
        ActionConfig config = config(20, 8);
        Verdict verdict = ActionJudge.judge(fixture.baseline(), config, fixture.source(fixture.reboardCandidate()));

        assertThat(verdict.code()).isEqualTo(ReasonCode.GO);
        assertThat(verdict.candidates().get(0).outcome().branches()).hasSize(3);
        List<String> lines = ActionGuidance.compose(verdict, config);
        assertAllowed(String.join("\n", lines));
        assertThat(lines).filteredOn(line -> line.startsWith("맞은편에서 약"))
            .hasSize(2).noneMatch(line -> line.startsWith("맞은편에서 약 10분"));
    }

    @Test
    void 대기_시도가_한_대면_다음_차_문구를_빼고_두_대면_둘_다_보인다() {
        ActionFixture oneCar = new ActionFixture();
        oneCar.stay = queried(entry("s0-3", 3));
        oneCar.stayProbabilities.clear();
        oneCar.stayProbabilities.add(marginal("s0-3", 0.8));
        ActionConfig config = config(15, 8);
        Verdict one = ActionJudge.judge(oneCar.baseline(), config, ActionFixture.neverCalled());

        ActionFixture twoCars = new ActionFixture();
        twoCars.stayProbabilities.set(0, marginal("s0-3", 0.5));
        twoCars.stayProbabilities.set(1, ActionFixture.conditional("s0-11", ValueTag.COND_FAIL, 0.5,
            Set.of("s0-3"), Set.of(), Optional.empty()));
        Verdict two = ActionJudge.judge(twoCars.baseline(), config, ActionFixture.neverCalled());

        assertThat(ActionGuidance.compose(one, config)).anyMatch(line -> line.contains("약 3분 뒤 차를 시도할 수 있어요"));
        assertThat(String.join("\n", ActionGuidance.compose(one, config))).doesNotContain("못 타면");
        assertThat(String.join("\n", ActionGuidance.compose(two, config))).contains("못 타면 약 11분");
    }

    @Test
    void 시도할_차가_없으면_입력된_차량_기준임을_밝히고_대기_문장을_쓰지_않는다() {
        ActionFixture fixture = new ActionFixture();
        fixture.stay = queried(entry("s0-30", 30));
        fixture.candidateProbabilities.add(marginal("w-9", 0.1));
        ActionConfig config = config(15, 8);
        Verdict verdict = ActionJudge.judge(fixture.baseline(), config, fixture.source(
            new WalkCandidate("walk", observed(1), queried(entry("w-9", 9)), Optional.of(0.0))));

        String text = String.join("\n", ActionGuidance.compose(verdict, config));
        assertThat(verdict.code()).isEqualTo(ReasonCode.STAY_GAIN_BELOW_THRESHOLD);
        assertThat(text).contains("입력된 차량 기준으로 15분 안에 이 정류장에서 시도할 차가 없어요");
        assertThat(text).doesNotContain("기다리");
    }

    @Test
    void 부분_비교이면_비교_가능한_경로_중이라고_밝힌다() {
        ActionFixture fixture = new ActionFixture();
        WalkCandidate known = new WalkCandidate("known", observed(1), queried(entry("k-9", 9)), Optional.of(0.0));
        WalkCandidate unknown = new WalkCandidate("unknown", observed(1),
            new Unavailable(MissingReason.QUERY_FAILED), Optional.of(0.0));
        fixture.candidateProbabilities.add(marginal("k-9", 0.8));
        ActionConfig config = config(15, 8);
        Verdict verdict = ActionJudge.judge(fixture.baseline(), config, fixture.source(known, unknown));

        assertThat(String.join("\n", ActionGuidance.compose(verdict, config))).contains("비교 가능한 경로 중");
    }

    @Test
    void 모든_사유_코드의_문장에_금지_표현과_금지_문자가_없다() {
        ActionConfig config = config(15, 8);
        List<Verdict> verdicts = new ArrayList<>();
        for (int scenario = 0; scenario < 7; scenario++) {
            verdicts.add(verdictOf(scenario, config));
        }
        assertThat(verdicts).extracting(Verdict::code).containsExactlyInAnyOrder(ReasonCode.values());
        for (Verdict verdict : verdicts) {
            String text = String.join("\n", ActionGuidance.compose(verdict, config));
            assertAllowed(text);
        }
    }

    private static void assertAllowed(String text) {
        assertThat(text).isNotBlank().doesNotContain(LONG_DASH, MIDDLE_DOT);
        FORBIDDEN_PHRASES.forEach(phrase -> assertThat(text).doesNotContain(phrase));
    }

    private static Verdict verdictOf(int scenario, ActionConfig config) {
        ActionFixture fixture = new ActionFixture();
        WalkCandidate walk = new WalkCandidate("walk", observed(1), queried(entry("w-9", 9)), Optional.of(0.0));
        fixture.candidateProbabilities.add(marginal("w-9", 0.8));
        return switch (scenario) {
            case 0 -> {
                fixture.stay = new Unavailable(MissingReason.QUERY_FAILED);
                yield ActionJudge.judge(fixture.baseline(), config, fixture.source());
            }
            case 1 -> {
                fixture.stayProbabilities.set(0, marginal("s0-3", 0.9));
                yield ActionJudge.judge(fixture.baseline(), config, fixture.source());
            }
            case 2 -> ActionJudge.judge(fixture.baseline(), config, fixture.source());
            case 3 -> ActionJudge.judge(fixture.baseline(), config(15, 0.5), fixture.source(walk));
            case 4 -> ActionJudge.judge(fixture.baseline(), config, ActionFixture.sourceOf(
                new ActionJudge.Failed(MissingReason.QUERY_FAILED)));
            case 5 -> {
                fixture.candidateProbabilities.add(marginal("w-weak", 0.01));
                yield ActionJudge.judge(fixture.baseline(), config, fixture.source(new WalkCandidate("weak",
                    observed(1), queried(entry("w-weak", 9)), Optional.of(0.0))));
            }
            default -> ActionJudge.judge(fixture.baseline(), config, fixture.source(walk));
        };
    }
}
