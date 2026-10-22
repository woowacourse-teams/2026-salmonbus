package com.gustler.backend.forecasting.infrastructure.observations;

import com.gustler.backend.forecasting.application.quality.TripQualityInvestigationService;
import com.gustler.backend.forecasting.domain.quality.QualityObservationBatch;
import com.gustler.backend.forecasting.domain.quality.QualityObservationBatch.Row;
import com.gustler.backend.observations.api.CollectionQualityHook;
import com.gustler.backend.observations.api.VehicleObservationsStored;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 저장된 관측을 품질 조사 입력으로 전달한다.
 * 수집과 품질 판정의 모듈 경계를 연결한다.
 */
@Component
public class CollectionQualityAdapter implements CollectionQualityHook {
    private final TripQualityInvestigationService investigations;

    public CollectionQualityAdapter(TripQualityInvestigationService investigations) {
        this.investigations = investigations;
    }

    @Override
    @Transactional(readOnly = false, propagation = Propagation.MANDATORY)
    public void observationsStored(VehicleObservationsStored stored) {
        investigations.observationsStored(
            new QualityObservationBatch(stored.batchId(), stored.routeVersionId(), stored.observedAt(),
                stored.rows()
                    .stream()
                    .map(row -> new Row(row.observationId(), row.vehicleId(), row.remainingSeats()))
                    .toList()));
    }
}
