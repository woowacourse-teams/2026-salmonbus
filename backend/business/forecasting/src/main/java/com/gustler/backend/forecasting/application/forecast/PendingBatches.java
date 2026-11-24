package com.gustler.backend.forecasting.application.forecast;

import java.time.Instant;
import java.util.List;

/**
 * 예보가 아직 처리하지 않은 배치를 찾는 포트다. 예보는 이 포트가 돌려준 순서대로 배치를 처리한다.
 *
 * <p>수집에 성공했지만 예보 실행 기록이 없는 배치를 찾는다. 행 잠금을 잡지 않는다.
 */
public interface PendingBatches {

    /**
     * 노선 판본의 처리하지 않은 배치를 수신 시각 순으로 읽는다.
     *
     * @param routeVersionId 노선 판본
     * @param notBefore 수신 시각 하한(포함). 신선도 창의 시작이다
     * @param limit 읽을 배치 수의 상한
     * @return 처리하지 않은 배치
     */
    List<Pending> find(long routeVersionId, Instant notBefore, int limit);

    /** 처리하지 않은 배치 하나다. */
    record Pending(long observationBatchId, long routeVersionId, Instant receivedAt) {

    }
}
