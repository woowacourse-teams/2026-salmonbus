package com.gustler.backend.forecasting.application.quality;

import com.gustler.backend.forecasting.api.quality.TripQualityPreview;
import com.gustler.backend.forecasting.api.quality.TripQualityStatus;
import com.gustler.backend.forecasting.domain.quality.QualityObservationBatch;
import com.gustler.backend.forecasting.domain.quality.TripQualityDiscovery;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TripQualityMaintenanceStore {
    record Batch(long id, Instant observedAt) { }
    record Signal(long batchId, QualityObservationBatch.Row row) { }
    TripQualityPreview preview(long version, Instant until);
    Optional<TripQualityDiscovery> discovery(long version);
    void startDiscovery(long version, TripQualityDiscovery discovery);
    List<Batch> discoveryPage(long version, TripQualityDiscovery discovery, int limit);
    List<Signal> anomalies(List<Long> batchIds);
    void saveDiscovery(long version, TripQualityDiscovery discovery);
    Optional<String> nextPendingVehicle(long version);
    boolean hasPendingVehicles(long version);
    List<TripQualityStatus> status(long version);
}
