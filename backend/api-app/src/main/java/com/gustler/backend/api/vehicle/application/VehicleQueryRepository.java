package com.gustler.backend.api.vehicle.application;

import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.vehicle.domain.ObservedVehicle;
import java.util.List;
import java.util.Optional;

/**
 * 최신 배치의 결과와 해당 배치의 차량 관측을 함께 읽는 경계다.
 */
public interface VehicleQueryRepository {
    Optional<VehicleSnapshot> findLatestSnapshot(RouteId routeId);

    List<ObservedVehicle> findVehicles(final long observationBatchId);
}
