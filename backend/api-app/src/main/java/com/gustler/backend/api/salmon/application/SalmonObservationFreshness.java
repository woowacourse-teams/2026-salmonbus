package com.gustler.backend.api.salmon.application;

import java.time.Instant;

/** 관측 유효 기간은 따로 두지 않는다. 스냅샷 staleAt부터는 그 예보를 쓰지 않는다. */
public final class SalmonObservationFreshness {
    private SalmonObservationFreshness() {
    }

    public static boolean usable(Instant decisionAt, Instant staleAt) {
        return decisionAt.isBefore(staleAt);
    }
}
