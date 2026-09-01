package com.gustler.backend.collector;

import java.util.Arrays;

/**
 * 이 주기로 하루를 다 돌면 호출을 몇 번 쓰는가.
 *
 * <p>노선을 늘리는 것은 설정 한 줄인데 주기를 같이 안 바꾸면 하루 한도가 터진다.
 * 실측 기준 노선 하나가 4,278회라 두 노선은 8,556회로 한도의 86퍼센트이고 세 노선은 넘는다.
 * 주석은 안 읽히므로 세는 것을 코드에 두고 넘치면 기동 때 경고한다.
 */
public final class CollectionSchedule {

    /** 이 주기표의 판본. 간격을 하나라도 바꾸면 올린다. 판마다 이 값을 남겨야 뒤에서 되짚는다. */
    public static final String CURRENT_STRATEGY_VERSION = "adaptive-kst-v1.0.1";

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
