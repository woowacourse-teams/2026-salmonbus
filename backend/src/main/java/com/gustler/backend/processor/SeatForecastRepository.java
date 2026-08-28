package com.gustler.backend.processor;

import java.time.Instant;
import java.util.List;

/**
 * 예보 행을 쓰고 닫는 포트.
 *
 * <p>한 판 쓰기 · 판에 예보 완료 찍기 · 회수 대상 읽기 · 회수 결과 반영을 한자리에 둔다.
 * 넷이 같은 행의 생애라 나누면 규칙이 갈라진다.
 *
 * <p>예보 완료 표시는 collector 가 소유한 observation_batch 에 있지만 processor 가 찍는다.
 * collector 코드를 부르지 않고 자기 포트로 쓴다.
 */
public interface SeatForecastRepository {

    /** 한 판의 예보 전부. 같은 관측과 같은 대상 정류장에는 한 줄만 남는다. */
    void save(
        List<SeatForecast> forecasts
    );

    /** 그 판의 예보를 다 썼다는 표시. 차가 0대라 예보 행이 없어도 찍는다. */
    void markForecastCompleted(
        long observationBatchId,
        Instant completedAt
    );

    /** 아직 라벨을 못 채운 예보를 오래된 것부터. */
    List<PendingForecast> findPending(
        long routeVersionId,
        int limit
    );

    /** 회수한 라벨을 예보 행에 채운다. */
    void settle(
        List<ForecastSettlement> settlements
    );
}
