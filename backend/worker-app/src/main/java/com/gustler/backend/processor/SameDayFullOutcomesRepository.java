package com.gustler.backend.processor;

import java.time.Instant;
import java.util.List;

/**
 * 당일 성적 집계를 담는 포트. 언제 읽고 언제 다시 셀지는 {@link SameDayFullOutcomesService} 가 정한다.
 */
public interface SameDayFullOutcomesRepository {

    List<SameDayFullOutcomeCount> findCounts(
        long routeId,
        SeoulDay day
    );

    /** 센 값으로 덮어쓴다. 원본이 진실이라 더하지 않는다. */
    void replaceCounts(
        long routeId,
        SeoulDay day,
        List<SameDayFullOutcomeCount> counts
    );

    /**
     * 닫힌 예보 하나를 집계에 더한다.
     *
     * <p>날짜와 반영 시각은 도착 관측이 실린 판이 응답을 받은 시각으로 정한다. 채점한 시각을 쓰면
     * 회수가 늦게 돌 때 아직 도착 안 한 것까지 센 것으로 남는다.
     */
    void add(
        SettledForecast settled
    );

    /** 집계를 거치지 않고 예보 표에서 직접 센다. */
    List<SameDayFullOutcomeCount> countFromSource(
        long routeId,
        SeoulDay day,
        Instant until
    );
}
