package com.gustler.backend.api.vehicle.application;

import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.vehicle.domain.ObservedVehicle;
import java.util.List;
import java.util.Optional;

public interface VehicleQueryRepository {

    Optional<VehicleSnapshot> findLatestSnapshot(RouteId routeId);

    List<ObservedVehicle> findVehicles(final long observationBatchId);
}
