package com.gustler.backend.observations.api;

import java.util.Set;

/** 관측 저장 트랜잭션에서 후속 업무에 전달할 좌석값을 판정한다. */
public interface CollectionQualityHook {
    /** 원본은 저장하되 후속 처리에는 좌석을 비울 관측 ID를 반환한다. */
    Set<Long> observationsStored(VehicleObservationsStored stored);
}
