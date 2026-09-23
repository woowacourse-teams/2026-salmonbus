package com.gustler.backend.forecasting.infrastructure.observations;

import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.application.quality.TripQualityInvestigationService;
import com.gustler.backend.forecasting.domain.quality.OneWayTripAssessment;
import com.gustler.backend.forecasting.domain.quality.QualityObservationBatch;
import com.gustler.backend.observations.api.CollectionQualityHook;
import com.gustler.backend.observations.api.ObservedSeatValue;
import com.gustler.backend.observations.api.VehicleObservationsStored;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class CollectionQualityAdapter implements CollectionQualityHook {
    private final RouteDataQualityAccess quality;
    private final TripQualityInvestigationService investigations;
    public CollectionQualityAdapter(final RouteDataQualityAccess quality, final TripQualityInvestigationService investigations) {
        this.quality = quality;
        this.investigations = investigations;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void beforeRowsStored(final long routeVersionId, final List<ObservedSeatValue> rows) {
        if (rows.stream().anyMatch(row -> OneWayTripAssessment.requiresInvestigation(row.vehicleId(), row.remainingSeats()))) {
            quality.lock(routeVersionId);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void observationsStored(final VehicleObservationsStored stored) {
        investigations.observationsStored(new QualityObservationBatch(stored.batchId(), stored.routeVersionId(), stored.observedAt(),
            stored.rows().stream().map(row -> new QualityObservationBatch.Row(row.observationId(), row.vehicleId(), row.remainingSeats())).toList()));
    }
}
