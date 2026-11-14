package com.gustler.backend.forecasting.application.pipeline;

import java.time.Instant;
import java.util.List;

/**
 * 본선({@code forecast.pipeline=v2})이 아직 처리하지 않은 배치를 찾는 포트다. 수집이 넘긴 신호를 잃었거나 처리 중에 죽은 배치를
 * 줍는 데 쓴다. 옛 경로의 "발행 행이 없는 배치" 조회와 같은 뜻이다.
 *
 * <p>"처리하지 않음"은 성공한 배치(줄 있음·빈 응답)인데 예보 실행 줄이 없고 옛 경로가 착수 시각({@code input_confirmed_at})을
 * 찍지도 않은 것이다. 옛 경로가 찍은 배치는 그 시각으로 이미 이력에 들어가 있으므로 본선이 새 시각으로 다시 처리하지 않는다.
 * 행 잠금을 잡지 않는다.
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
