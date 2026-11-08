package com.gustler.backend.api.salmon.domain;

import static com.gustler.backend.api.salmon.domain.ActionFixture.config;
import static com.gustler.backend.api.salmon.domain.ActionFixture.entry;
import static com.gustler.backend.api.salmon.domain.ActionFixture.marginal;
import static com.gustler.backend.api.salmon.domain.ActionFixture.observed;
import static com.gustler.backend.api.salmon.domain.ActionFixture.queried;
import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.salmon.domain.ActionModel.*;
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
            "정해진 하차 정류장에서 내려요", "길을 건너", "약 14분 뒤 차를 시도해요", "약 19분 뒤 차를 시도할 수 있어요",
            "입력된 차량 기준", "구간시간은 추정", "연결 여유는 약 1.4분", "근거가 부족해요");
        assertThat(text).doesNotContain("확실히");
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
            assertThat(text).isNotBlank().doesNotContain(LONG_DASH, MIDDLE_DOT);
            FORBIDDEN_PHRASES.forEach(phrase -> assertThat(text).doesNotContain(phrase));
        }
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
