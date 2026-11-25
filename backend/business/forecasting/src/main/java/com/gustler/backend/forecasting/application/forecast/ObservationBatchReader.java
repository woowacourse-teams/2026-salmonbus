package com.gustler.backend.forecasting.application.forecast;

import java.util.Optional;

/**
 * 관측 배치 하나를 저장된 모든 행과 같이 읽는 포트다. 예보가 큐에서 꺼낸 배치를 처리할 때 쓴다.
 *
 * <p>행의 순서와 읽는 열은 복구({@link ReplaySource})와 같아야 한다. 같은 배치를 복구로 읽든 이 포트로 읽든
 * 예보 단계가 받는 {@link ForecastBatch}가 같아야 이어 온 상태와 복구한 상태가 같아진다. 확정 조건으로 거르지 않으며,
 * 처리 시작 시각이 아직 없으면 비어 있다. 행 잠금을 사용하지 않고 배치를 고치지 않는다.
 */
public interface ObservationBatchReader {

    /**
     * 배치를 읽는다.
     *
     * @param observationBatchId 관측 배치
     * @return 배치가 없으면 빈 값
     */
    Optional<ObservationBatchRead> readBatch(long observationBatchId);
}
