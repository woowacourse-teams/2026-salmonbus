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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class LiveVehicleQueryService {

    private static final Logger log = LoggerFactory.getLogger(LiveVehicleQueryService.class);

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
        if (vehicles.isEmpty()) {
            return unreadableObservation(snapshot, observedAt, staleAt);
        }
        return overviewOf(
            snapshot,
            VehicleObservationState.VEHICLES_PRESENT,
            observedAt,
            staleAt,
            vehicles
        );
    }

    /**
     * 상류가 차량 행을 줬는데 우리가 하나도 못 읽은 상태.
     *
     * <p>"차가 없다"(NO_VEHICLES_OBSERVED)로 내면 안 된다. 상류가 없다고 한 적이 없다.
     * 상류가 필드 형식을 바꾸면 그때부터 모든 행이 걸러지는데, 0대로 보이면 이상을 알아챌 수가 없다.
     * 지금 무엇이 다니는지 말해줄 수 없다는 뜻이라 UNKNOWN 이 맞는 자리다.
     *
     * <p>계속 나면 상류 계약이 바뀐 것이다. 운영이 알아채라고 WARN 을 남긴다.
     */
    private LiveVehicleOverview unreadableObservation(
        VehicleSnapshot snapshot,
        OffsetDateTime observedAt,
        OffsetDateTime staleAt
    ) {
        log.warn(
            "상류가 차량 행을 줬는데 읽어낸 관측이 없다. 노선={} 묶음={}",
            snapshot.routeId(),
            snapshot.latestBatchId());
        return overviewOf(
            snapshot,
            VehicleObservationState.UNKNOWN,
            observedAt,
            staleAt,
            List.of()
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
