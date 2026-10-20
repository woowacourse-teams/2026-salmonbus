package com.gustler.backend.observations.domain;

import java.util.Arrays;

/**
 * 수집 주기에 따른 하루 호출 횟수를 계산한다.
 *
 * <p>설정에서 노선을 늘릴 때 수집 주기도 조정하지 않으면 하루 한도를 초과한다.
 * 수집 소요시간을 제외한 계획값은 노선당 하루 3,018회다. 재시도와 노선 정보 조회는 별도다.
 * 호출 횟수를 코드로 계산해 기동 시 한도 초과 여부를 경고한다.
 */
public final class CollectionSchedule {
    /** 수집 주기의 버전. 간격을 하나라도 바꾸면 올린다. 나중에 확인할 수 있도록 수집 배치마다 기록한다. */
    public static final String CURRENT_STRATEGY_VERSION = "adaptive-kst-v1.0.2";

    private CollectionSchedule() {
    }

    public static int dailyCallsPerRoute() {
        return Arrays.stream(CollectionPhase.values())
            .mapToInt(CollectionPhase::dailyCallsPerRoute)
            .sum();
    }

    public static int dailyCallsFor(
        final int routeCount
    ) {
        return dailyCallsPerRoute() * routeCount;
    }

    public static boolean fitsDailyLimit(
        final int routeCount,
        final int dailyLimit
    ) {
        return dailyCallsFor(routeCount) <= dailyLimit;
    }
}
