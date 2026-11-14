package com.gustler.backend.forecasting.infrastructure.pipeline;

import com.gustler.backend.forecasting.application.pipeline.VehicleCapacities;
import com.gustler.backend.forecasting.infrastructure.capacity.VehicleCapacityCache;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 옛 경로와 같은 {@link VehicleCapacityCache#findKnownAt}으로 예보 시점에 알려진 정원을 찾는다. */
public final class CachedVehicleCapacities implements VehicleCapacities {
    private final VehicleCapacityCache cache;

    public CachedVehicleCapacities(VehicleCapacityCache cache) {
        this.cache = cache;
    }

    @Override
    public Map<String, Integer> knownAt(String sourceId, List<String> vehicleIds, Instant at, final long observationBatchId) {
        Map<String, Integer> seats = new HashMap<>();
        cache.findKnownAt(sourceId, vehicleIds, at, observationBatchId)
            .forEach((vehicle, capacity) -> seats.put(vehicle, capacity.seats()));
        return Map.copyOf(seats);
    }
}
