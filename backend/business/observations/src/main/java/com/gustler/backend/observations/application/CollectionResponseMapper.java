package com.gustler.backend.observations.application;

import com.gustler.backend.gbis.api.GbisLocationResult;
import com.gustler.backend.gbis.api.dto.BusLocationResponse.BusLocation;
import com.gustler.backend.observations.domain.CollectedObservations;
import com.gustler.backend.observations.domain.ObservationBatchConclusion;
import java.util.List;

/** 외부 응답을 수집 업무가 사용하는 결과와 관측으로 변환한다. */
public interface CollectionResponseMapper {
    ObservationBatchConclusion conclusionOf(GbisLocationResult response);
    CollectedObservations observationsOf(List<BusLocation> rows);
}
