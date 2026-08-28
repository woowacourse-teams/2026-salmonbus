package com.gustler.backend.collector;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;

/**
 * 시각대마다 수집을 얼마나 자주 부르는가.
 *
 * <p>균일 주기로는 안 된다. 라벨 조건이 "연속 관측 간격 90초 이하" 라 첨두를 느리게 하면
 * 학습에 쓸 관측이 자주 탈락하고, 반대로 심야까지 촘촘히 부르면 하루 한도 10,000회 안에
 * 두 노선이 안 들어간다.
 *
 * <p>15~20초 구간에서는 상류 순번이 두 칸 이상 건너뛴 적이 0건이라 좌석 감소를 쓸 수 있다.
 * 2분 폴링에서는 23.2퍼센트가 두 칸 이상 건너뛰었다.
 *
 * <p>낮(09~17)은 티켓 표에 빠져 있던 구간이다. 같은 티켓이 낮 밀도를 180회/시로 적고 있고
 * 3600 을 180 으로 나누면 20초다. 아침·저녁 240회/시가 15초, 심야 6회/시가 600초인 것과 맞는다.
 */
public enum CollectionPhase {

    /** 심야. 쿼터를 아끼는 자리다. */
    DEEP_NIGHT(1, 4, 600),
    EARLY_MORNING(4, 7, 20),
    MORNING_PEAK(7, 9, 15),
    DAYTIME(9, 17, 20),
    EVENING_PEAK(17, 20, 15),
    LATE_EVENING(20, 23, 15),
    /** 심야 꼬리. 23시에 시작해 자정을 넘어 1시에 끝난다. */
    NIGHT_TAIL(23, 1, 20),
    ;

    private static final int SECONDS_PER_HOUR = 3600;
    private static final int HOURS_PER_DAY = 24;

    private final int fromHour;
    private final int untilHour;
    private final int intervalSeconds;

    CollectionPhase(
        final int fromHour,
        final int untilHour,
        final int intervalSeconds
    ) {
        this.fromHour = fromHour;
        this.untilHour = untilHour;
        this.intervalSeconds = intervalSeconds;
    }

    /** 한국 시각으로 고른다. 서버 타임존이나 세계 표준시 날짜와 다르다. */
    public static CollectionPhase at(
        Instant at,
        Clock clock
    ) {
        return atHour(at.atZone(clock.getZone()).getHour());
    }

    static CollectionPhase atHour(
        final int hour
    ) {
        return Arrays.stream(values())
            .filter(phase -> phase.covers(hour))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("%d 시를 담는 수집 단계가 없다".formatted(hour)));
    }

    public int intervalSeconds() {
        return intervalSeconds;
    }

    /** 이 단계만 하루 종일 돈다면 노선 하나를 몇 번 부르나. */
    public int dailyCallsPerRoute() {
        return coveredHours() * SECONDS_PER_HOUR / intervalSeconds;
    }

    private int coveredHours() {
        if (fromHour < untilHour) {
            return untilHour - fromHour;
        }
        return HOURS_PER_DAY - fromHour + untilHour;
    }

    private boolean covers(
        final int hour
    ) {
        if (fromHour < untilHour) {
            return fromHour <= hour && hour < untilHour;
        }
        return fromHour <= hour || hour < untilHour;
    }
}
