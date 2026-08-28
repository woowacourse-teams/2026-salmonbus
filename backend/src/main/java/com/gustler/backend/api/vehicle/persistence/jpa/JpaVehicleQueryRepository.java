package com.gustler.backend.api.vehicle.persistence.jpa;

import com.gustler.backend.api.http.ServiceUnavailableException;
import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.route.persistence.jpa.RouteVersionJpaEntity;
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

@Repository
@RequiredArgsConstructor
public class JpaVehicleQueryRepository implements VehicleQueryRepository {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final List<String> NORMAL_OUTCOMES = List.of(
        VehiclePollOutcome.SUCCESS_ROWS.name(),
        VehiclePollOutcome.SUCCESS_EMPTY.name()
    );
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
                .toList();
        } catch (DataAccessException exception) {
            throw new ServiceUnavailableException();
        }
    }

    private VehicleSnapshot toSnapshot(
        RouteId routeId,
        RouteVersionJpaEntity routeVersion
    ) {
        Optional<ObservationBatchJpaEntity> latestBatch = firstBatchOf(
            observationBatchRepository.findLatestByRouteVersion(
                routeVersion,
                FIRST_RESULT_PAGE
            )
        );
        Optional<ObservationBatchJpaEntity> latestNormalBatch = firstBatchOf(
            observationBatchRepository.findLatestNormalByRouteVersion(
                routeVersion,
                NORMAL_OUTCOMES,
                FIRST_RESULT_PAGE
            )
        );

        return new VehicleSnapshot(
            routeId.value(),
            String.valueOf(routeVersion.id()),
            latestBatch.map(ObservationBatchJpaEntity::id).orElse(null),
            latestBatch.filter(ObservationBatchJpaEntity::hasResponseReceivedAt)
                .map(ObservationBatchJpaEntity::outcome)
                .map(VehiclePollOutcome::fromDatabaseValue)
                .orElse(VehiclePollOutcome.UNKNOWN),
            latestBatch.map(ObservationBatchJpaEntity::effectivePollAt)
                .map(this::inSeoul)
                .orElse(null),
            latestNormalBatch.map(ObservationBatchJpaEntity::responseReceivedAt)
                .map(this::inSeoul)
                .orElse(null)
        );
    }

    private OffsetDateTime inSeoul(OffsetDateTime dateTime) {
        return dateTime.atZoneSameInstant(SEOUL).toOffsetDateTime();
    }

    private Optional<ObservationBatchJpaEntity> firstBatchOf(
        List<ObservationBatchJpaEntity> batches
    ) {
        return batches.stream().findFirst();
    }
}
