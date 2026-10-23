package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.observations.api.ObservationDeliveryBatch;

/** 커밋된 관측을 수집 시점의 품질 버전과 함께 캐시에 전달한다. */
public interface ObservationDeliveryReceiver {
    /**
     * 커밋된 관측 전달을 받아 평가용 메모리에 반영한다. 외부 원천을 다시 호출하지 않는다.
     */
    boolean receive(long qualityRevision, ObservationDeliveryBatch batch);
}
