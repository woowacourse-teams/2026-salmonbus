package com.gustler.backend.api.vehicle.application;

import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.route.RouteNotFoundException;
import com.gustler.backend.api.vehicle.domain.ObservedVehicle;
import com.gustler.backend.api.vehicle.domain.VehicleObservationState;
import com.gustler.backend.api.vehicle.domain.VehiclePollOutcome;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class LiveVehicleQueryService {

    private final VehicleQueryRepository vehicleQueryRepository;
    private final VehicleFreshnessPolicy freshnessPolicy;
    private final VehicleCachePolicy cachePolicy;
    private final Clock clock;

    public LiveVehicleOverview getLiveVehicles(RouteId routeId) {
        VehicleSnapshot snapshot = vehicleQueryRepository.findLatestSnapshot(routeId)
            .orElseThrow(RouteNotFoundException::new);
        OffsetDateTime observedAt = snapshot.observedAt();
        OffsetDateTime staleAt = observedAt == null
            ? null
            : freshnessPolicy.staleAt(observedAt);
        OffsetDateTime cacheReferenceAt = snapshot.latestPollAt() == null
            ? OffsetDateTime.now(clock)
            : snapshot.latestPollAt();

        if (!isUsableLatestSnapshot(snapshot, staleAt)) {
            return overviewOf(
                snapshot,
                VehicleObservationState.UNKNOWN,
                observedAt,
                staleAt,
                List.of(),
                cacheReferenceAt
            );
        }

        if (snapshot.latestOutcome() == VehiclePollOutcome.SUCCESS_EMPTY) {
            return overviewOf(
                snapshot,
                VehicleObservationState.NO_VEHICLES_OBSERVED,
                observedAt,
                staleAt,
                List.of(),
                cacheReferenceAt
            );
        }

        List<ObservedVehicle> vehicles = vehicleQueryRepository.findVehicles(
            snapshot.latestBatchId()
        );
        return overviewOf(
            snapshot,
            VehicleObservationState.VEHICLES_PRESENT,
            observedAt,
            staleAt,
            vehicles,
            cacheReferenceAt
        );
    }

    private boolean isUsableLatestSnapshot(
        VehicleSnapshot snapshot,
        OffsetDateTime staleAt
    ) {
        if (!snapshot.latestOutcome().isNormal()
            || snapshot.latestBatchId() == null
            || staleAt == null) {
            return false;
        }
        return !freshnessPolicy.isStale(staleAt);
    }

    private LiveVehicleOverview overviewOf(
        VehicleSnapshot snapshot,
        VehicleObservationState state,
        OffsetDateTime observedAt,
        OffsetDateTime staleAt,
        List<ObservedVehicle> vehicles,
        OffsetDateTime cacheReferenceAt
    ) {
        return new LiveVehicleOverview(
            snapshot.routeId(),
            snapshot.referenceVersionId(),
            state,
            observedAt,
            staleAt,
            vehicles,
            cachePolicy.maxAgeAt(cacheReferenceAt)
        );
    }
}
