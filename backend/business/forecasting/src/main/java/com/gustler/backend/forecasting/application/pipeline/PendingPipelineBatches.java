package com.gustler.backend.forecasting.application.pipeline;

import java.time.Instant;
import java.util.List;

/**
 * 예보가 아직 처리하지 않은 배치를 찾는 포트다. 예보는 이 포트가 돌려준 순서대로 배치를 처리한다.
 *
 * <p>"처리하지 않음"은 성공한 배치(줄 있음·빈 응답)인데 예보 실행 줄이 없고 배치에 착수 시각({@code input_confirmed_at})도 없는
 * 것이다. 예보 실행 기록이 없던 때에 처리된 배치는 그 열에 착수 시각이 있고, 그 시각으로 이미 이력에 들어가 있으므로 새 시각으로
 * 다시 처리하지 않는다. 행 잠금을 잡지 않는다.
 */
public interface PendingPipelineBatches {
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
