package com.gustler.backend.observations.api;

import com.gustler.backend.observations.domain.CollectionPhase;
import com.gustler.backend.observations.domain.CollectionSchedule;
import java.time.Clock;
import java.time.Instant;

/** 실행 앱이 수집 주기와 하루 예상 호출 횟수를 조회하는 계약. */
public final class CollectionTiming {
    private CollectionTiming() {
    }

    public static int intervalSeconds(Instant at, Clock clock) {
        return CollectionPhase.at(at, clock).intervalSeconds();
    }

    public static int dailyCallsFor(final int routeCount) {
        return CollectionSchedule.dailyCallsFor(routeCount);
    }

    public static boolean fitsDailyLimit(final int routeCount, final int dailyLimit) {
        return CollectionSchedule.fitsDailyLimit(routeCount, dailyLimit);
    }
}
