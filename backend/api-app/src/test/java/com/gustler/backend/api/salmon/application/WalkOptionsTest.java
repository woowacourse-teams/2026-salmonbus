package com.gustler.backend.api.salmon.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.boarding.domain.AccessMode;
import com.gustler.backend.api.boarding.domain.BoardingOptions;
import com.gustler.backend.api.boarding.domain.BoardingOptions.Access;
import com.gustler.backend.api.boarding.domain.BoardingOptions.Leg;
import com.gustler.backend.api.boarding.domain.BoardingOptions.Option;
import com.gustler.backend.api.boarding.domain.StopRelation;
import com.gustler.backend.api.boarding.domain.StopVisit;
import com.gustler.backend.api.salmon.application.WalkOption.Relation;
import java.util.List;
import org.junit.jupiter.api.Test;

class WalkOptionsTest {
    @Test
    void 응답_항목의_정류장_순번_관계_가장_빠른_도보_초를_순서대로_옮긴다() {
        BoardingOptions boarding = new BoardingOptions("204000057", 9L, visit("origin", 5, "UP"), List.of(
            new Option(visit("up", 4, "UP"), -1, StopRelation.BEFORE, List.of(walk(540), walk(600))),
            new Option(visit("down", 9, "UP"), 4, StopRelation.AFTER, List.of(walk(180))),
            new Option(visit("opp", 3, "DOWN"), -2, StopRelation.OPPOSITE, List.of(walk(90))),
            new Option(visit("same", 8, "UP"), 3, StopRelation.SAME_STATION, List.of(walk(0)))));

        assertThat(WalkOptions.from(boarding)).containsExactly(
            new WalkOption("up", 4, Relation.BEFORE, 540),
            new WalkOption("down", 9, Relation.AFTER, 180),
            new WalkOption("opp", 3, Relation.OPPOSITE, 90),
            new WalkOption("same", 8, Relation.SAME_STATION, 0));
    }

    @Test
    void 후보가_없으면_빈_목록이다() {
        BoardingOptions boarding = new BoardingOptions("204000057", 9L, visit("origin", 5, "UP"), List.of());

        assertThat(WalkOptions.from(boarding)).isEmpty();
    }

    private static StopVisit visit(String stationId, int stopSequence, String direction) {
        return new StopVisit(stationId, stopSequence, stationId, direction, true, null, null);
    }

    private static Access walk(int seconds) {
        return Access.of(List.of(new Leg(AccessMode.WALK, seconds, null, "test")));
    }
}
