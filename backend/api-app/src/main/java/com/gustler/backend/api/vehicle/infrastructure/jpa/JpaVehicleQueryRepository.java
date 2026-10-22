package com.gustler.backend.api.vehicle.infrastructure.jpa;

import com.gustler.backend.api.error.ServiceUnavailableException;
import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.route.infrastructure.jpa.RouteVersionJpaEntity;
import com.gustler.backend.api.vehicle.application.VehicleQueryRepository;
import com.gustler.backend.api.vehicle.application.VehicleSnapshot;
import com.gustler.backend.api.vehicle.domain.ObservedVehicle;
import com.gustler.backend.api.vehicle.domain.VehiclePollOutcome;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

/**
 * 최신 배치 결과와 그 배치의 차량을 같은 조회 경계에서 읽는다.
 * 관측 시각은 응답 계약의 시간대로 변환한다.
 */
@Repository
@RequiredArgsConstructor
public class JpaVehicleQueryRepository implements VehicleQueryRepository {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Pageable FIRST_RESULT_PAGE = PageRequest.of(0, 1);

    private final VehicleRouteVersionEntityRepository routeVersionRepository;
    private final ObservationBatchEntityRepository observationBatchRepository;
    private final VehicleObservationEntityRepository vehicleObservationRepository;

    @Override
    public Optional<VehicleSnapshot> findLatestSnapshot(RouteId routeId) {
        try {
            return routeVersionRepository
                .findByRoute_SourceRouteIdAndValidToIsNull(routeId.value())
                .map(routeVersion -> toSnapshot(routeId, routeVersion));
        } catch (DataAccessException exception) {
            throw new ServiceUnavailableException();
        }
    }

    @Override
    public List<ObservedVehicle> findVehicles(final long observationBatchId) {
        try {
            return vehicleObservationRepository.findAllByBatchId(observationBatchId)
                .stream()
                .map(VehicleObservationJpaEntity::toDomain)
                .flatMap(Optional::stream)
                .toList();
        } catch (DataAccessException exception) {
            throw new ServiceUnavailableException();
        }
    }

    private VehicleSnapshot toSnapshot(
        RouteId routeId,
        RouteVersionJpaEntity routeVersion
    ) {
        Optional<ObservationBatchJpaEntity> latestSuccessfulBatch =
            observationBatchRepository.findLatestSuccessfulByRouteVersion(
                routeVersion,
                FIRST_RESULT_PAGE
            )
                .stream()
                .findFirst();

        return new VehicleSnapshot(
            routeId.value(),
            String.valueOf(routeVersion.id()),
            latestSuccessfulBatch.map(ObservationBatchJpaEntity::id).orElse(null),
            latestSuccessfulBatch
                .map(ObservationBatchJpaEntity::outcome)
                .map(VehiclePollOutcome::fromDatabaseValue)
                .orElse(VehiclePollOutcome.UNKNOWN),
            latestSuccessfulBatch.map(ObservationBatchJpaEntity::responseReceivedAt)
                .map(this::inSeoul)
                .orElse(null)
        );
    }

    private OffsetDateTime inSeoul(OffsetDateTime dateTime) {
        return dateTime.atZoneSameInstant(SEOUL).toOffsetDateTime();
    }
}
