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

/**
 * 최신 수집 결과의 성공 여부와 유효 시간을 먼저 확인한 뒤 차량을 제공한다.
 * 해석 실패를 차량이 없는 정상 관측으로 바꾸지 않는다.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
@RequiredArgsConstructor
public class LiveVehicleQueryService {
    private static final Logger LOGGER = LoggerFactory.getLogger(LiveVehicleQueryService.class);

    private final VehicleQueryRepository vehicleQueryRepository;
    private final VehicleFreshnessPolicy freshnessPolicy;
    private final VehicleCachePolicy cachePolicy;
    private final Clock clock;

    /**
     * 현재 판본의 최신 관측을 조회해 화면 상태를 계산한다. 모형 지원 여부와 관측 부재·신선도를 별도로 판단한다.
     */
    public LiveVehicleOverview getLiveVehicles(RouteId routeId) {
        VehicleSnapshot snapshot = vehicleQueryRepository.findLatestSnapshot(routeId)
            .orElseThrow(RouteNotFoundException::new);
        OffsetDateTime observedAt = snapshot.observedAt();
        OffsetDateTime staleAt = null;
        if (observedAt != null) {
            staleAt = freshnessPolicy.staleAt(observedAt);
        }

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
     * 상류가 보낸 차량 행을 하나도 해석하지 못한 상태다.
     *
     * <p>상류가 차량 부재를 알린 적이 없으므로 NO_VEHICLES_OBSERVED로 응답하면 안 된다.
     * 필드 형식이 바뀌어 모든 행이 걸러진 상황을 0대로 표시하면 이상을 알아채기 어렵다.
     * 현재 운행 상태를 알 수 없으므로 UNKNOWN으로 응답한다.
     *
     * <p>이 상태가 계속되면 상류 계약이 바뀐 것이므로 운영에서 확인할 수 있도록 WARN을 남긴다.
     */
    private LiveVehicleOverview unreadableObservation(
        VehicleSnapshot snapshot,
        OffsetDateTime observedAt,
        OffsetDateTime staleAt
    ) {
        LOGGER.warn(
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
        if (snapshot.latestBatchId() == null || staleAt == null) {
            return false;
        }
        if (!snapshot.latestOutcome().isNormal()) {
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
