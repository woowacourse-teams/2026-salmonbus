package com.gustler.backend.forecasting.application.pipeline;

import com.gustler.backend.forecasting.api.pipeline.ForecastPublished;
import com.gustler.backend.forecasting.api.pipeline.ObservationBatchSeen;
import com.gustler.backend.forecasting.api.pipeline.PassClosed;
import java.util.List;

/**
 * 예보 단계가 저장을 커밋한 뒤 사건을 뒤 단계(채점)에 넘기는 포트다. 커밋하지 못한 사건은 넘기지 않는다.
 *
 * <p>예보 단계의 스레드에서 배치 하나마다 {@link #batchSeen}, {@link #passesClosed}, {@link #forecastPublished} 순서로
 * 부른다. 채점 단계가 같은 배치의 사건을 이 순서로 받아야 하기 때문이다(그 배치가 닫은 통과를 먼저 반영한 뒤 그 배치의 예측을
 * 기다리게 한다). 예보 단계가 되감으며 되찾은 통과는 그 배치의 사건보다 먼저 {@link #passesClosed}로 넘기며, 닫힌 시각이 이미 넘긴
 * 사건의 시각보다 이를 수 있다.
 * 구현은 오래 막지 말아야 하며, 던진 예외는 부른 쪽이 로그로 남기고 삼킨다. 같은 사건을 다시 받아도 결과가 같아야 한다.
 */
public interface PipelineEvents {
    /**
     * 예보 실행을 저장한 배치를 알린다. 예측을 내지 않은 실행도 알린다.
     *
     * @param seen 배치 사건
     */
    void batchSeen(ObservationBatchSeen seen);

    /**
     * 배치 하나의 예보 실행을 알린다.
     *
     * @param published 저장한 예보 실행과 버스별 예측
     */
    void forecastPublished(ForecastPublished published);

    /**
     * 이 배치로 닫힌 통과를 알린다. 닫힌 통과가 없으면 부르지 않는다.
     *
     * @param passes 저장한 통과
     */
    void passesClosed(List<PassClosed> passes);
}
