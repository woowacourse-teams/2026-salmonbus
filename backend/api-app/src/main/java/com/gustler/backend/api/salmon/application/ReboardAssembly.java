package com.gustler.backend.api.salmon.application;

import com.gustler.backend.api.salmon.domain.Candidates.ReboardCandidate;
import com.gustler.backend.api.salmon.domain.Fields.Field;
import com.gustler.backend.api.salmon.domain.Fields.Observed;
import com.gustler.backend.api.salmon.domain.StopLists.StopList;
import com.gustler.backend.api.salmon.domain.Timing.Span;
import java.util.Objects;
import java.util.Optional;

/** 이미 고른 하차 구간으로 재탑승 후보 하나를 만든다. 하차 정류장 도착은 조회하지 않는다. */
public final class ReboardAssembly {
    private ReboardAssembly() {
    }

    public static ReboardCandidate candidate(AlightingLeg leg, StopList opposite, StopList reboard,
                                             Optional<Double> detour) {
        Objects.requireNonNull(opposite);
        Objects.requireNonNull(reboard);
        Objects.requireNonNull(detour);
        return new ReboardCandidate(idOf(leg), observed(leg.walkToOppositeSeconds()), observed(leg.rideSeconds()),
            observed(leg.walkAfterSeconds()), opposite, reboard, detour);
    }

    static String idOf(AlightingLeg leg) {
        return String.join("-", "reboard", leg.oppositeStationId(), Integer.toString(leg.oppositeSequence()),
            Integer.toString(leg.walkToOppositeSeconds()), leg.alightStationId(), Integer.toString(leg.alightSequence()),
            Integer.toString(leg.rideSeconds()), leg.reboardStationId(), Integer.toString(leg.reboardSequence()),
            Integer.toString(leg.walkAfterSeconds()));
    }

    private static Field<Span> observed(int seconds) {
        return new Observed<>(new Span(seconds * 1000L));
    }
}
