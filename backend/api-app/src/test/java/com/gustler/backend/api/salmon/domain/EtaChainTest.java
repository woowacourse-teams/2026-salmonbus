package com.gustler.backend.api.salmon.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.api.salmon.domain.EtaChain.Arrival;
import com.gustler.backend.api.salmon.domain.EtaChain.Known;
import com.gustler.backend.api.salmon.domain.EtaChain.Observation;
import com.gustler.backend.api.salmon.domain.EtaChain.Pattern;
import com.gustler.backend.api.salmon.domain.EtaChain.Reason;
import com.gustler.backend.api.salmon.domain.EtaChain.Run;
import com.gustler.backend.api.salmon.domain.EtaChain.Segment;
import com.gustler.backend.api.salmon.domain.EtaChain.Unresolved;
import com.gustler.backend.api.salmon.domain.EtaChain.Visit;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EtaChainTest {
    private static final Instant BASE = Instant.parse("2026-11-08T00:00:00Z");
    private static final Pattern UP = new Pattern("route", "version", "UP", "pattern");
    private static final Run VEHICLE = new Run(UP, "vehicle", "trip");

    @Test
    void 같은_차량의_절대_도착_예측을_차분한다() {
        Segment segment = known(EtaChain.sameRun(observation("five", VEHICLE, 5, 5),
            observation("eight", VEHICLE, 8, 11)));
        assertThat(segment.travelTime()).isEqualTo(Duration.ofMinutes(6));
        assertThat(segment.evidence()).containsExactly("five", "eight");
    }

    @Test
    void 다른_차량을_같은_운행으로_차분하지_않는다() {
        Run other = new Run(UP, "other", "trip");
        assertThat(EtaChain.sameRun(observation("five", VEHICLE, 5, 5),
            observation("eight", other, 8, 11))).isEqualTo(new Unresolved<>(Reason.RUN_MISMATCH));
    }

    @Test
    void 같은_차량의_다른_운행도_구분한다() {
        Run nextTrip = new Run(UP, "vehicle", "next-trip");
        assertThat(EtaChain.sameRun(observation("five", VEHICLE, 5, 5),
            observation("eight", nextTrip, 8, 11))).isEqualTo(new Unresolved<>(Reason.RUN_MISMATCH));
    }

    @Test
    void 서로_다른_정보_시각을_조용히_차분하지_않는다() {
        Observation later = new Observation("later", VEHICLE, visit(8), BASE.plusSeconds(30),
            BASE.plusSeconds(660), Optional.of(Duration.ofSeconds(5)));
        assertThat(EtaChain.sameRun(observation("five", VEHICLE, 5, 5), later))
            .isEqualTo(new Unresolved<>(Reason.INFORMATION_MISMATCH));
    }

    @Test
    void 시각_정렬은_잔여분이_아닌_절대시각을_쓰고_갱신_오차를_더한다() {
        Observation later = new Observation("later", VEHICLE, visit(8), BASE.plusSeconds(30),
            BASE.plusSeconds(690), Optional.of(Duration.ofSeconds(5)));
        Segment segment = known(EtaChain.align(observation("five", VEHICLE, 5, 5), later,
            Duration.ofSeconds(20), "revision-sample"));
        assertThat(segment.travelTime()).isEqualTo(Duration.ofSeconds(390));
        assertThat(segment.errorBound()).contains(Duration.ofSeconds(30));
    }

    @Test
    void 오차_근거가_없는_정렬은_결측이다() {
        Observation unknown = new Observation("unknown", VEHICLE, visit(5), BASE,
            BASE.plusSeconds(300), Optional.empty());
        assertThat(EtaChain.align(unknown, observation("eight", VEHICLE, 8, 11),
            Duration.ofSeconds(20), "revision-sample"))
            .isEqualTo(new Unresolved<>(Reason.NO_ERROR_BOUND));
    }

    @Test
    void 음수_차분을_영으로_보정하지_않는다() {
        assertThat(EtaChain.sameRun(observation("five", VEHICLE, 5, 11),
            observation("eight", VEHICLE, 8, 5)))
            .isEqualTo(new Unresolved<>(Reason.PREDICTION_CONFLICT));
    }

    @Test
    void 역방향_순번을_거부한다() {
        assertThat(EtaChain.sameRun(observation("eight", VEHICLE, 8, 5),
            observation("five", VEHICLE, 5, 11)))
            .isEqualTo(new Unresolved<>(Reason.SEQUENCE_MISMATCH));
    }

    @Test
    void 관측된_차량의_가려진_세번째_도착을_구간_연결로_복원한다() {
        Observation anchor = observation("v-five", VEHICLE, 5, 5);
        Segment first = known(EtaChain.sameRun(anchor, observation("v-eight", VEHICLE, 8, 11)));
        Run other = new Run(UP, "other", "other-trip");
        Segment second = known(EtaChain.sameRun(observation("u-eight", other, 8, 2),
            observation("u-ten", other, 10, 6)));
        Segment chain = known(EtaChain.stitch(List.of(first, second), Duration.ofSeconds(20), "transfer-study"));
        Arrival restored = known(EtaChain.restore(anchor, chain));
        assertThat(restored.arrivalAt()).isEqualTo(BASE.plusSeconds(900));
        assertThat(restored.run()).isEqualTo(VEHICLE);
        assertThat(restored.visit()).isEqualTo(visit(10));
        assertThat(restored.errorBound()).contains(Duration.ofSeconds(35));
        assertThat(restored.evidence()).containsExactly("v-five", "v-eight", "u-eight", "u-ten", "transfer-study");
    }

    @Test
    void 끊긴_구간을_이어붙이지_않는다() {
        Segment first = segment(5, 7);
        Segment second = segment(8, 10);
        assertThat(EtaChain.stitch(List.of(first, second), Duration.ZERO, "transfer-study"))
            .isEqualTo(new Unresolved<>(Reason.COVER_BROKEN));
    }

    @Test
    void 다른_방향의_구간을_섞지_않는다() {
        Pattern down = new Pattern("route", "version", "DOWN", "pattern");
        Run other = new Run(down, "other", "other-trip");
        Segment second = known(EtaChain.sameRun(observation("eight", other, 8, 2),
            observation("ten", other, 10, 6)));
        assertThat(EtaChain.stitch(List.of(segment(5, 8), second), Duration.ZERO, "transfer-study"))
            .isEqualTo(new Unresolved<>(Reason.PATTERN_MISMATCH));
    }

    @Test
    void 구간_오차_상계가_없으면_이어붙이지_않는다() {
        Observation unknown = new Observation("unknown", VEHICLE, visit(5), BASE,
            BASE.plusSeconds(300), Optional.empty());
        Segment first = known(EtaChain.sameRun(unknown, observation("eight", VEHICLE, 8, 11)));
        assertThat(EtaChain.stitch(List.of(first, segment(8, 10)), Duration.ZERO, "transfer-study"))
            .isEqualTo(new Unresolved<>(Reason.NO_ERROR_BOUND));
    }

    @Test
    void 다른_차량의_단일_구간을_이전_근거_없이_복원에_쓰지_않는다() {
        Run other = new Run(UP, "other", "other-trip");
        assertThat(EtaChain.restore(observation("anchor", other, 5, 5), segment(5, 8)))
            .isEqualTo(new Unresolved<>(Reason.RUN_MISMATCH));
    }

    @Test
    void 같은_차량이어도_새_관측에_예전_차분을_그대로_붙이지_않는다() {
        Observation refreshed = observation("refreshed", VEHICLE, 5, 6);
        assertThat(EtaChain.restore(refreshed, segment(5, 8)))
            .isEqualTo(new Unresolved<>(Reason.INFORMATION_MISMATCH));
    }

    @Test
    void 빈_구간_목록은_성공한_복원이_아니다() {
        assertThat(EtaChain.stitch(List.of(), Duration.ZERO, "transfer-study"))
            .isEqualTo(new Unresolved<>(Reason.COVER_BROKEN));
    }

    @Test
    void 음수_오차_상계를_거부한다() {
        assertThatThrownBy(() -> EtaChain.stitch(List.of(segment(5, 8)),
            Duration.ofSeconds(-1), "transfer-study")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 물리적_차량_식별자가_없으면_관측을_만들지_못한다() {
        assertThatThrownBy(() -> new Run(UP, "", "trip"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 복원_시각의_오차에는_상쇄되는_기준_관측의_오차를_넣지_않는다() {
        Observation anchor = new Observation("v-five", VEHICLE, visit(5), BASE, BASE.plusSeconds(300),
            Optional.of(Duration.ofSeconds(60)));
        Observation later = new Observation("v-eight", VEHICLE, visit(8), BASE, BASE.plusSeconds(660),
            Optional.of(Duration.ofSeconds(60)));
        Segment segment = known(EtaChain.sameRun(anchor, later));

        Arrival restored = known(EtaChain.restore(anchor, segment));

        assertThat(segment.errorBound()).contains(Duration.ofSeconds(120));
        assertThat(restored.arrivalAt()).isEqualTo(later.arrivalAt());
        assertThat(restored.errorBound()).contains(Duration.ofSeconds(60));
    }

    @Test
    void 기준_관측의_오차가_구간에_든_값보다_크면_같은_관측일_수_없어_복원하지_않는다() {
        Observation wide = new Observation("from-5", VEHICLE, visit(5), BASE, BASE.plusSeconds(300),
            Optional.of(Duration.ofSeconds(60)));

        assertThat(EtaChain.restore(wide, segment(5, 8))).isEqualTo(new Unresolved<>(Reason.INFORMATION_MISMATCH));
    }

    private static Segment segment(int from, int to) {
        return known(EtaChain.sameRun(observation("from-" + from, VEHICLE, from, from),
            observation("to-" + to, VEHICLE, to, to)));
    }

    private static Observation observation(String id, Run run, int sequence, int minutes) {
        return new Observation(id, run, visit(sequence), BASE, BASE.plusSeconds(minutes * 60L),
            Optional.of(Duration.ofSeconds(5)));
    }

    private static Visit visit(int sequence) {
        return new Visit("station-" + sequence, sequence);
    }

    private static <T> T known(EtaChain.Result<T> result) {
        assertThat(result).isInstanceOf(Known.class);
        return ((Known<T>) result).value();
    }
}
