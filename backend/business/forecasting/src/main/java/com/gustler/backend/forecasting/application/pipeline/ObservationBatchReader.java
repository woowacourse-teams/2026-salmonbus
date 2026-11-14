package com.gustler.backend.forecasting.application.pipeline;

import java.util.Optional;

/**
 * 관측 배치 하나를 저장된 줄 전부와 함께 읽는 포트다. 그림자 실행이 옛 경로가 끝낸 배치를 새 경로에 넣을 때 쓴다.
 *
 * <p>줄의 순서와 읽는 열은 되감기({@link ReplaySource})와 같아야 한다. 같은 배치를 되감기로 읽든 이 포트로 읽든
 * 예보 단계가 받는 {@link ForecastBatch}가 같아야 이어 온 상태와 되감은 상태가 같아진다. 확정 조건으로 거르지 않으며,
 * 착수 시각이 아직 없으면 비어 있다. 행 잠금을 잡지 않고 배치를 고치지 않는다.
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
