package com.gustler.backend.api.salmon.application;

import static com.gustler.backend.api.salmon.domain.ActionFixture.config;
import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.salmon.application.WalkOption.Relation;
import com.gustler.backend.api.salmon.domain.ActionFixture;
import com.gustler.backend.api.salmon.domain.ActionJudge;
import com.gustler.backend.api.salmon.domain.ActionJudge.Listed;
import com.gustler.backend.api.salmon.domain.ActionModel.*;
import com.gustler.backend.api.salmon.domain.EtaChain;
import com.gustler.backend.api.salmon.domain.EtaChain.Known;
import com.gustler.backend.api.salmon.domain.EtaChain.Observation;
import com.gustler.backend.api.salmon.domain.EtaChain.Pattern;
import com.gustler.backend.api.salmon.domain.EtaChain.Run;
import com.gustler.backend.api.salmon.domain.EtaChain.Visit;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CandidateAssemblerTest {
    private static final Instant INFORMATION = Instant.parse("2026-11-08T09:00:00Z");

    @Test
    void 같은_방향_후보만_직접_도보_후보로_만들고_도보_초를_구간으로_옮긴다() {
        List<WalkOption> options = List.of(
            new WalkOption("A", 4, Relation.BEFORE, 540),
            new WalkOption("B", 9, Relation.AFTER, 600),
            new WalkOption("C", 5, Relation.OPPOSITE, 120),
            new WalkOption("D", 6, Relation.SAME_STATION, 0),
            new WalkOption("E", 7, Relation.parse("TELEPORT"), 30));
        Listed listed = CandidateAssembler.assemble("route", INFORMATION, options, StopArrivalSource.unavailable(),
            ProbabilitySupply.none());

        assertThat(listed.candidates()).extracting(Candidate::id).containsExactly("walk-A-4", "walk-B-9");
        WalkCandidate first = (WalkCandidate) listed.candidates().get(0);
        assertThat(first.walk()).isEqualTo(new Observed<>(new Span(540_000)));
        assertThat(first.detour()).isEmpty();
    }

    @Test
    void 도착_소스와_확률_소스가_없으면_판정은_지어내지_않고_근거_부족으로_끝난다() {
        ActionFixture fixture = new ActionFixture();
        Listed listed = CandidateAssembler.assemble("route", INFORMATION, List.of(new WalkOption("A", 4, Relation.BEFORE, 60)),
            StopArrivalSource.unavailable(), ProbabilitySupply.none());
        Verdict verdict = ActionJudge.judge(fixture.baseline(), config(15, 10), epoch -> listed);

        assertThat(verdict.code()).isEqualTo(ReasonCode.ABSTAIN_INSUFFICIENT_EVIDENCE);
        assertThat(verdict.candidates()).singleElement()
            .satisfies(record -> assertThat(record.outcome().evaluation()).isInstanceOf(Insufficient.class));
    }

    @Test
    void 도착_소스의_목록을_후보에_그대로_붙인다() {
        StopList arrivals = ActionFixture.queried(ActionFixture.entry("walk-9", 9));
        StopArrivalSource source = (routeId, stationId, stopSequence, decisionAt) -> arrivals;
        Listed listed = CandidateAssembler.assemble("route", INFORMATION, List.of(new WalkOption("A", 4, Relation.AFTER, 60)), source,
            ProbabilitySupply.none());

        assertThat(((WalkCandidate) listed.candidates().get(0)).list()).isSameAs(arrivals);
    }

    @Test
    void ETA_이어붙이기로_만든_구간시간으로_가상_픽스처가_GO까지_간다() {
        Pattern pattern = new Pattern("route", "version", "UP", "pattern");
        Run vehicle = new Run(pattern, "vehicle-1", "trip-1");
        Observation atOpposite = new Observation("obs-opp", vehicle, new Visit("opposite", 1), INFORMATION,
            INFORMATION.plusSeconds(4 * 60), Optional.empty());
        Observation atAlight = new Observation("obs-off", vehicle, new Visit("alight", 2), INFORMATION,
            INFORMATION.plusSeconds(9 * 60), Optional.empty());
        ActionFixture fixture = new ActionFixture();
        fixture.ride = EtaFields.rideTime(EtaChain.sameRun(atOpposite, atAlight));

        Verdict verdict = ActionJudge.judge(fixture.baseline(), config(20, 8),
            fixture.source(fixture.reboardCandidate()));

        assertThat(fixture.ride).isEqualTo(new Derived<>(new Span(300_000), new NoBound()));
        assertThat(verdict.code()).isEqualTo(ReasonCode.GO);
        assertThat(verdict.delta().orElseThrow()).isBetween(0.346, 0.348);
    }

    @Test
    void 같은_운행이_아니면_구간시간이_결측이라_재탑승은_근거_부족이다() {
        Pattern pattern = new Pattern("route", "version", "UP", "pattern");
        Observation atOpposite = new Observation("obs-opp", new Run(pattern, "vehicle-1", "trip-1"),
            new Visit("opposite", 1), INFORMATION, INFORMATION.plusSeconds(240), Optional.empty());
        Observation atAlight = new Observation("obs-off", new Run(pattern, "vehicle-2", "trip-1"),
            new Visit("alight", 2), INFORMATION, INFORMATION.plusSeconds(540), Optional.empty());
        ActionFixture fixture = new ActionFixture();
        fixture.ride = EtaFields.rideTime(EtaChain.sameRun(atOpposite, atAlight));

        Verdict verdict = ActionJudge.judge(fixture.baseline(), config(20, 8),
            fixture.source(fixture.reboardCandidate()));

        assertThat(fixture.ride).isEqualTo(new Missing<Span>(MissingReason.UNIDENTIFIED_VEHICLE));
        assertThat(verdict.code()).isEqualTo(ReasonCode.ABSTAIN_INSUFFICIENT_EVIDENCE);
    }
}
