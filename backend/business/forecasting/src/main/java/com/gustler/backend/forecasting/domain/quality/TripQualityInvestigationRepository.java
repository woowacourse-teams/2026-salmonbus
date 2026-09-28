package com.gustler.backend.forecasting.domain.quality;

import java.util.List;
import java.util.Optional;

public interface TripQualityInvestigationRepository {
    record Key(long routeVersionId, String vehicleId) { }
    Optional<Key> nextPending();
    Optional<TripQualityInvestigation> findPending(long routeVersionId, String vehicleId);
    List<String> activeVehicleIds(long routeVersionId);
    void saveStart(TripQualityInvestigation investigation);
    void save(TripQualityInvestigation investigation);
}
