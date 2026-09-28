package com.gustler.backend.observations.api;

public interface CollectionQualityHook {
    void observationsStored(VehicleObservationsStored stored);
}
