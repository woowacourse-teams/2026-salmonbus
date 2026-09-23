package com.gustler.backend.forecasting.domain.evaluation;


import java.time.Instant;
import java.util.List;

/**
 * 당일 평가 집계의 조회·초기화·증분 저장을 담당한다.
 */
public interface SameDayFullOutcomesRepository {

    /** 초기화와 증분 반영을 같은 노선 품질 잠금으로 직렬화한다. */
    void lockRoute(long routeId);

    List<SameDayFullOutcomeCount> findCounts(
        long routeId,
        SeoulDay day
    );

    /** 원본에서 다시 집계한 값으로 교체한다. 기존 누계에 더하지 않는다. */
    void upsertCounts(
        long routeId,
        SeoulDay day,
        List<SameDayFullOutcomeCount> counts
    );

    /**
     * 평가가 확정된 예보 하나를 집계에 더한다.
     *
     * <p>날짜와 반영 시각은 도착 관측이 실린 batch 가 응답을 받은 시각으로 정한다. 채점한 시각을 쓰면
     * 평가가 늦게 실행될 때 아직 도착하지 않은 관측까지 집계한 것처럼 기록된다.
     */
    void add(
        SettledForecast settled
    );

    /** 저장된 누계 대신 현재 품질 조건에 맞는 평가 결과에서 직접 집계한다. */
    List<SameDayFullOutcomeCount> countFromSource(
        long routeId,
        SeoulDay day,
        Instant until
    );
}
