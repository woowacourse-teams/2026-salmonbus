package com.gustler.backend.processor;

import java.time.Instant;
import com.gustler.backend.processor.seatdistribution.SameDayFullOutcomes;
import java.util.List;
import java.util.Map;

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

    /**
     * 아직 안 닫힌 예보가 남아 있는 노선 판본.
     *
     * <p>지금 쓰는 판본만 묻지 않는다. 노선이 개편되면 옛 판본의 예보가 그대로 남는데,
     * 그 행들도 닫아야 안 닫힌 행이 영영 쌓이지 않는다.
     */
    List<Long> findRouteVersionIdsWithPendingForecasts();

    /** 아직 라벨을 못 채운 예보를 오래된 것부터. */
    List<PendingForecast> findPending(
        long routeVersionId,
        int limit
    );

    /** 회수한 라벨을 예보 행에 채운다. */
    void settle(
        List<ForecastSettlement> settlements
    );

    /**
     * 오늘 이미 도착이 확인된 예보들의 성적. 예보 거리마다 하나씩.
     *
     * <p>만석 확률을 당일 성적으로 옮기는 데 쓴다. 예보가 오늘따라 만석을 계속 놓치고 있으면
     * 확률을 올리고 반대면 내린다.
     *
     * <p><b>예보 시각보다 뒤에 도착한 것과 다른 날짜 것은 안 센다.</b> 섞이면 아직 모르는 것을
     * 알고 쓰는 셈이 된다. 자르는 것은 이 질의가 하고, 모델은 잘린 값만 받는다.
     *
     * @param predictionAt 이 시각까지 도착이 확인된 것만. 그 batch 가 상류에서 응답을 받은 시각이다
     */
    Map<Integer, SameDayFullOutcomes> readSameDayFullOutcomes(
        long routeVersionId,
        Instant predictionAt
    );
}
