package com.gustler.backend.api.board.persistence.jpa;

import com.gustler.backend.api.board.application.BoardQueryRepository;
import com.gustler.backend.api.board.application.BoardSnapshot;
import com.gustler.backend.api.board.application.DepartureSchedule;
import com.gustler.backend.api.board.application.SnapshotObservation;
import com.gustler.backend.api.board.application.StoredPrediction;
import com.gustler.backend.api.board.domain.BoardStop;
import com.gustler.backend.api.board.domain.ForecastModel;
import com.gustler.backend.api.http.ServiceUnavailableException;
import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.route.persistence.jpa.ModelDeploymentEntityRepository;
import com.gustler.backend.api.route.persistence.jpa.ModelDeploymentJpaEntity;
import com.gustler.backend.api.route.persistence.jpa.ModelDeploymentState;
import com.gustler.backend.api.route.persistence.jpa.RouteVersionJpaEntity;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JpaBoardQueryRepository implements BoardQueryRepository {

    private static final Pageable FIRST_RESULT = PageRequest.of(0, 1);

    private final Clock clock;
    private final BoardRouteVersionEntityRepository routeVersionRepository;
    private final BoardObservationBatchEntityRepository observationBatchRepository;
    private final BoardRouteStopEntityRepository routeStopRepository;
    private final SeatForecastEntityRepository seatForecastRepository;
    private final ModelDeploymentEntityRepository modelDeploymentRepository;

    @Override
    public Optional<BoardSnapshot> findSnapshot(
        RouteId routeId
    ) {
        try {
            return routeVersionRepository.findCurrent(routeId.value())
                .map(this::toSnapshot);
        } catch (DataAccessException exception) {
            throw new ServiceUnavailableException();
        }
    }

    @Override
    public List<BoardStop> findStops(
        final long routeVersionId
    ) {
        try {
            return routeStopRepository.findAllByRouteVersionId(routeVersionId)
                .stream()
                .map(RouteStopJpaEntity::toDomain)
                .toList();
        } catch (DataAccessException exception) {
            throw new ServiceUnavailableException();
        }
    }

    @Override
    public List<StoredPrediction> findPredictions(
        final long observationBatchId
    ) {
        try {
            return seatForecastRepository.findAllByBatchId(observationBatchId)
                .stream()
                .map(forecast -> forecast.toDomain(clock.getZone()))
                .toList();
        } catch (DataAccessException exception) {
            throw new ServiceUnavailableException();
        }
    }

    private BoardSnapshot toSnapshot(
        RouteVersionJpaEntity routeVersion
    ) {
        Optional<SnapshotObservation> observation = observationBatchRepository
            .findLatestForecastCompleted(routeVersion, FIRST_RESULT)
            .stream()
            .findFirst()
            .map(batch -> batch.toDomain(clock.getZone()));
        Optional<ForecastModel> activeModel = modelDeploymentRepository
            .findByState(ModelDeploymentState.ACTIVE)
            .map(this::toModel);

        return new BoardSnapshot(
            routeVersion.id(),
            routeVersion.toRoute(),
            routeVersion.turnSequence(),
            new DepartureSchedule(
                routeVersion.upFirstDepartureTime(),
                routeVersion.upLastDepartureTime(),
                routeVersion.downFirstDepartureTime(),
                routeVersion.downLastDepartureTime()
            ),
            observation,
            activeModel
        );
    }

    private ForecastModel toModel(
        ModelDeploymentJpaEntity model
    ) {
        return new ForecastModel(
            model.id(),
            model.releaseId(),
            model.dataUntil().atZoneSameInstant(clock.getZone()).toOffsetDateTime()
        );
    }
}
