package com.gustler.backend.forecasting.infrastructure.capacity;

import com.gustler.backend.forecasting.application.forecast.VehicleCapacities;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** {@link VehicleCapacityCache#findKnownAt}으로 예보 시점에 알려진 정원을 찾는다. */
public final class CachedVehicleCapacities implements VehicleCapacities {

    private final VehicleCapacityCache cache;

    public CachedVehicleCapacities(VehicleCapacityCache cache) {
        this.cache = cache;
    }

    @Override
    public Map<String, Integer> knownAt(
        String sourceId,
        List<String> vehicleIds,
        Instant at,
        final long observationBatchId
    ) {
        return cache.findKnownAt(
                sourceId,
                vehicleIds,
                at,
                observationBatchId
        )
            .entrySet()
            .stream()
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> entry.getValue().seats()));
    }
}
