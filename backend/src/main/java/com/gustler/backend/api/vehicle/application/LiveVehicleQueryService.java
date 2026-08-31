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

        if (!isUsableLatestSnapshot(snapshot, staleAt)) {
            return overviewOf(
                snapshot,
                VehicleObservationState.UNKNOWN,
                observedAt,
                staleAt,
                List.of()
            );
        }

        if (snapshot.latestOutcome() == VehiclePollOutcome.SUCCESS_EMPTY) {
            return overviewOf(
                snapshot,
                VehicleObservationState.NO_VEHICLES_OBSERVED,
                observedAt,
                staleAt,
                List.of()
            );
        }

        List<ObservedVehicle> vehicles = vehicleQueryRepository.findVehicles(
            snapshot.latestBatchId()
        );
        return overviewOf(
            snapshot,
            stateOf(vehicles),
            observedAt,
            staleAt,
            vehicles
        );
    }

    /**
     * 상류가 차를 줬어도 내보낼 것이 하나도 없으면 "본 차가 없다" 다.
     * VEHICLES_PRESENT 에 빈 배열을 실어 보내면 계약이 어긋난다.
     *
     * <p>비는 길이 둘이다. 저장할 때 뺀 행(SAL-84)과 읽을 때 거른 행(운행 상태를 해석 못 한 경우)이다.
     * stored_rows 를 읽는 대신 실제로 내보낼 목록을 보면 둘 다 덮인다.
     */
    private VehicleObservationState stateOf(
        List<ObservedVehicle> vehicles
    ) {
        if (vehicles.isEmpty()) {
            return VehicleObservationState.NO_VEHICLES_OBSERVED;
        }
        return VehicleObservationState.VEHICLES_PRESENT;
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
        List<ObservedVehicle> vehicles
    ) {
        return new LiveVehicleOverview(
            snapshot.routeId(),
            snapshot.referenceVersionId(),
            state,
            observedAt,
            staleAt,
            vehicles,
            cachePolicy.maxAgeAt(OffsetDateTime.now(clock))
        );
    }
}
