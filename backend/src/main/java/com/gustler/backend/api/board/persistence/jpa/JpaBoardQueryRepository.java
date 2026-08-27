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
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
public class JpaBoardQueryRepository implements BoardQueryRepository {

    private static final List<String> SUCCESSFUL_OUTCOMES = List.of(
        "SUCCESS_ROWS",
        "SUCCESS_EMPTY"
    );
    private static final Pageable FIRST_RESULT = PageRequest.of(0, 1);

    private final BoardRouteVersionEntityRepository routeVersionRepository;
    private final ObservationBatchEntityRepository observationBatchRepository;
    private final RouteStopEntityRepository routeStopRepository;
    private final SeatForecastEntityRepository seatForecastRepository;
    private final ModelDeploymentEntityRepository modelDeploymentRepository;

    public JpaBoardQueryRepository(
        final BoardRouteVersionEntityRepository routeVersionRepository,
        final ObservationBatchEntityRepository observationBatchRepository,
        final RouteStopEntityRepository routeStopRepository,
        final SeatForecastEntityRepository seatForecastRepository,
        final ModelDeploymentEntityRepository modelDeploymentRepository
    ) {
        this.routeVersionRepository = routeVersionRepository;
        this.observationBatchRepository = observationBatchRepository;
        this.routeStopRepository = routeStopRepository;
        this.seatForecastRepository = seatForecastRepository;
        this.modelDeploymentRepository = modelDeploymentRepository;
    }

    @Override
    public Optional<BoardSnapshot> findSnapshot(final RouteId routeId) {
        try {
            return routeVersionRepository.findCurrent(routeId.value())
                .map(this::toSnapshot);
        } catch (final DataAccessException exception) {
            throw new ServiceUnavailableException();
        }
    }

    @Override
    public List<BoardStop> findStops(final long routeVersionId) {
        try {
            return routeStopRepository.findAllByRouteVersionId(routeVersionId)
                .stream()
                .map(RouteStopJpaEntity::toDomain)
                .toList();
        } catch (final DataAccessException exception) {
            throw new ServiceUnavailableException();
        }
    }

    @Override
    public List<StoredPrediction> findPredictions(final long observationBatchId) {
        try {
            return seatForecastRepository.findAllByBatchId(observationBatchId)
                .stream()
                .map(SeatForecastJpaEntity::toDomain)
                .toList();
        } catch (final DataAccessException exception) {
            throw new ServiceUnavailableException();
        }
    }

    private BoardSnapshot toSnapshot(final RouteVersionJpaEntity routeVersion) {
        final Optional<SnapshotObservation> observation = observationBatchRepository
            .findLatestCompleted(routeVersion, SUCCESSFUL_OUTCOMES, FIRST_RESULT)
            .stream()
            .findFirst()
            .map(ObservationBatchJpaEntity::toDomain);
        final Optional<ForecastModel> activeModel = modelDeploymentRepository
            .findByState(ModelDeploymentState.ACTIVE)
            .map(this::toModel);

        return new BoardSnapshot(
            routeVersion.id(),
            routeVersion.route().toDomain(),
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

    private ForecastModel toModel(final ModelDeploymentJpaEntity model) {
        return new ForecastModel(model.id(), model.releaseId(), model.dataUntil());
    }
}
