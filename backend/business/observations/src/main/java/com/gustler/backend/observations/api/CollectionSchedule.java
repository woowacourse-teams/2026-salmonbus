package com.gustler.backend.observations.api;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;

/**
 * 한국 시각 기준 수집 간격. 07~09시와 17~23시는 20초, 01~04시는 600초,
 * 나머지 시간대는 30초다. 한 차례 수집이 끝난 시각을 기준으로 다음 간격을 정한다.
 */
public enum CollectionSchedule {

    /** 심야에는 호출 한도를 아끼도록 수집 간격을 늘린다. */
    DEEP_NIGHT(1, 4, 600),
    EARLY_MORNING(4, 7, 30),
    MORNING_PEAK(7, 9, 20),
    DAYTIME(9, 17, 30),
    EVENING_PEAK(17, 20, 20),
    LATE_EVENING(20, 23, 20),
    /** 자정을 지나는 심야 구간. 23시에 시작해 다음 날 1시에 끝난다. */
    NIGHT_TAIL(23, 1, 30),
    ;

    /** 간격 변경 시 수집 배치에 남기는 전략 버전. */
    public static final String CURRENT_STRATEGY_VERSION = "adaptive-kst-v1.0.2";

    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private static final int SECONDS_PER_HOUR = 3600;
    private static final int HOURS_PER_DAY = 24;

    private final int fromHour;
    private final int untilHour;
    private final int intervalSeconds;

    CollectionSchedule(final int fromHour, final int untilHour, final int intervalSeconds) {
        this.fromHour = fromHour;
        this.untilHour = untilHour;
        this.intervalSeconds = intervalSeconds;
    }

    /** 실행 간격과 일일 호출 수가 같은 시간대 규칙을 사용한다. */
    public static int intervalSeconds(Instant at) {
        return at(at).intervalSeconds();
    }

    public static int dailyCallsPerRoute() {
        return Arrays.stream(values())
            .mapToInt(CollectionSchedule::dailyCallsInSlot)
            .sum();
    }

    public static int dailyCallsFor(final int routeCount) {
        return dailyCallsPerRoute() * routeCount;
    }

    public static boolean fitsDailyLimit(final int routeCount, final int dailyLimit) {
        return dailyCallsFor(routeCount) <= dailyLimit;
    }

    /** 수집 시간대는 한국 시각으로 정한다. 서버의 기본 시간대나 UTC 날짜를 사용하지 않는다. */
    public static CollectionSchedule at(Instant at) {
        return atHour(at.atZone(KOREA).getHour());
    }

    static CollectionSchedule atHour(final int hour) {
        return Arrays.stream(values())
            .filter(phase -> phase.covers(hour))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("%d시가 포함된 수집 시간대가 없다".formatted(hour)));
    }

    public int intervalSeconds() {
        return intervalSeconds;
    }

    /** 하루 중 이 단계가 적용되는 시간 동안 노선 하나를 호출하는 횟수. */
    public int dailyCallsInSlot() {
        return coveredHours() * SECONDS_PER_HOUR / intervalSeconds;
    }

    private int coveredHours() {
        if (fromHour < untilHour) {
            return untilHour - fromHour;
        }
        return HOURS_PER_DAY - fromHour + untilHour;
    }

    private boolean covers(final int hour) {
        if (fromHour < untilHour) {
            return fromHour <= hour && hour < untilHour;
        }
        return fromHour <= hour || hour < untilHour;
    }
}
