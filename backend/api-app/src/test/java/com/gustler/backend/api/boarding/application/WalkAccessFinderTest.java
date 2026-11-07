package com.gustler.backend.api.boarding.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.api.boarding.domain.AccessMode;
import com.gustler.backend.api.boarding.domain.BoardingOptions.Access;
import com.gustler.backend.api.boarding.domain.StopVisit;
import com.gustler.backend.api.boarding.domain.WalkPair;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WalkAccessFinderTest {
    private static final StopVisit ORIGIN = visit("205000231", 5, 127.1487833, 37.4146333);
    private static final StopVisit BEFORE = visit("205000222", 4, 127.1530667, 37.4146);
    private static final StopVisit ACROSS = visit("205000226", 81, 127.14865, 37.4145333);
    private static final AccessConstraints FIFTEEN_MINUTES = new AccessConstraints(900);
    private final List<WalkPair> pairs = new ArrayList<>();
    private final WalkAccessFinder finder = new WalkAccessFinder(stationId -> pairs.stream()
        .filter(pair -> pair.stationA().equals(stationId) || pair.stationB().equals(stationId)).toList(), 1.06);

    private static StopVisit visit(String station, final int sequence, Double x, Double y) {
        return new StopVisit(station, sequence, station + "-이름", "UP", true, x, y);
    }

    private static WalkPair pair(StopVisit one, StopVisit two, final int meters) {
        StopVisit a = one.stationId().compareTo(two.stationId()) < 0 ? one : two;
        StopVisit b = a == one ? two : one;
        return new WalkPair(a.stationId(), b.stationId(), a.x(), a.y(), b.x(), b.y(), meters);
    }

    @Test
    void 저장한_도보_거리로_도보_한_구간짜리_이동_방법을_만든다() {
        // given
        pairs.addAll(List.of(pair(ORIGIN, BEFORE, 420), pair(ORIGIN, ACROSS, 103)));

        // when
        Map<StopVisit, List<Access>> result = finder.find(ORIGIN, List.of(BEFORE, ACROSS), FIFTEEN_MINUTES);

        // then
        assertThat(finder.requiredModes()).containsExactly(AccessMode.WALK);
        assertThat(result.get(ACROSS)).singleElement().satisfies(access -> {
            assertThat(access.durationSeconds()).isEqualTo(98);
            assertThat(access.legs()).singleElement().satisfies(leg -> {
                assertThat(leg.mode()).isEqualTo(AccessMode.WALK);
                assertThat(leg.durationSeconds()).isEqualTo(98);
                assertThat(leg.distanceMeters()).isEqualTo(103);
                assertThat(leg.source()).isEqualTo("PEDESTRIAN_GRAPH");
            });
        });
        assertThat(result.get(BEFORE).getFirst().durationSeconds()).isEqualTo(397);
    }

    @Test
    void 상한을_넘거나_쌍이_없는_후보는_결과에_없다() {
        // given
        pairs.add(pair(ORIGIN, BEFORE, 420));

        // when
        Map<StopVisit, List<Access>> withinFive = finder.find(ORIGIN, List.of(BEFORE, ACROSS), new AccessConstraints(300));
        Map<StopVisit, List<Access>> withinFifteen = finder.find(ORIGIN, List.of(BEFORE, ACROSS), FIFTEEN_MINUTES);

        // then
        assertThat(withinFive).isEmpty();
        assertThat(withinFifteen).containsOnlyKeys(BEFORE);
    }

    @Test
    void 계산_좌표에서_25m_넘게_옮겨진_정류장의_쌍은_쓰지_않는다() {
        // given
        StopVisit moved = visit(ACROSS.stationId(), ACROSS.stopSequence(), ACROSS.x() + 0.0004, ACROSS.y());
        pairs.add(pair(ORIGIN, ACROSS, 103));

        // when
        Map<StopVisit, List<Access>> result = finder.find(ORIGIN, List.of(moved), FIFTEEN_MINUTES);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    void 같은_정류장의_다른_방문은_도보_0초다() {
        // given
        StopVisit again = visit(ORIGIN.stationId(), 60, ORIGIN.x(), ORIGIN.y());

        // when
        Map<StopVisit, List<Access>> result = finder.find(ORIGIN, List.of(again), FIFTEEN_MINUTES);

        // then
        assertThat(result.get(again)).singleElement().satisfies(access -> {
            assertThat(access.durationSeconds()).isZero();
            assertThat(access.legs().getFirst().distanceMeters()).isZero();
        });
    }

    @Test
    void 걷는_속도는_양수여야_한다() {
        assertThatThrownBy(() -> new WalkAccessFinder(stationId -> List.of(), 0))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
