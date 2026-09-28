package com.gustler.backend.forecasting.infrastructure.observations;

import com.gustler.backend.forecasting.application.quality.TripQualityInvestigationService;
import com.gustler.backend.forecasting.domain.quality.QualityObservationBatch;
import com.gustler.backend.observations.api.CollectionQualityHook;
import com.gustler.backend.observations.api.VehicleObservationsStored;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class CollectionQualityAdapter implements CollectionQualityHook {
    private final TripQualityInvestigationService investigations;
    public CollectionQualityAdapter(final TripQualityInvestigationService investigations) {
        this.investigations = investigations;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void observationsStored(final VehicleObservationsStored stored) {
        investigations.observationsStored(new QualityObservationBatch(stored.batchId(), stored.routeVersionId(), stored.observedAt(),
            stored.rows().stream().map(row -> new QualityObservationBatch.Row(row.observationId(), row.vehicleId(), row.remainingSeats())).toList()));
    }
}
