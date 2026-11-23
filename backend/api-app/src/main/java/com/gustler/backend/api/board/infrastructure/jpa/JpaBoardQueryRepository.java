package com.gustler.backend.api.board.infrastructure.jpa;

import com.gustler.backend.api.board.application.BoardQueryRepository;
import com.gustler.backend.api.board.application.BoardSnapshot;
import com.gustler.backend.api.board.application.BoardVehicleObservation;
import com.gustler.backend.api.board.application.StoredPrediction;
import com.gustler.backend.api.board.domain.BoardStop;
import com.gustler.backend.api.board.infrastructure.jdbc.JdbcBoardSnapshotReader;
import com.gustler.backend.api.board.infrastructure.jdbc.JdbcForecastRecordReader;
import com.gustler.backend.api.error.ServiceUnavailableException;
import com.gustler.backend.api.route.RouteId;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Repository;

/**
 * 쓰기 모듈의 엔티티를 참조하지 않고 탑승판에 필요한 노선·모델·관측·예보를 조회한다.
 *
 * <p>스냅샷은 {@link JdbcBoardSnapshotReader}, 예보는 {@link JdbcForecastRecordReader}로 읽는다.
 * DB 조회가 실패하면 예보 없음으로 다루지 않고 서비스 불가로 바꾼다.
 */
@Repository
@RequiredArgsConstructor
public class JpaBoardQueryRepository implements BoardQueryRepository {
    private final BoardRouteStopEntityRepository routeStopRepository;
    private final BoardVehicleObservationEntityRepository vehicleObservationRepository;
    private final JdbcBoardSnapshotReader snapshotReader;
    private final JdbcForecastRecordReader recordReader;

    @Override
    public Optional<BoardSnapshot> findSnapshot(
        RouteId routeId
    ) {
        try {
            return snapshotReader.findSnapshot(routeId);
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
    public List<StoredPrediction> findRecordedPredictions(final long observationBatchId, LocalDate sourceDate) {
        return findRecordedPredictions(Map.of(observationBatchId, sourceDate))
            .getOrDefault(observationBatchId, List.of());
    }

    @Override
    public List<BoardVehicleObservation> findObservedVehicles(final long observationBatchId,
        final long routeVersionId) {
        try {
            return vehicleObservationRepository.findAllByBatchIdAndRouteVersionId(observationBatchId, routeVersionId)
                .stream()
                .map(VehicleObservationJpaEntity::toDomain)
                .toList();
        } catch (DataAccessException exception) {
            throw new ServiceUnavailableException();
        }
    }

    @Override
    public void limitStatementTime(Duration limit) {
        try {
            snapshotReader.limitStatementTime(limit);
        } catch (DataAccessException exception) {
            throw new ServiceUnavailableException();
        }
    }

    @Override
    public List<BoardSnapshot> findSnapshots(Collection<RouteId> routeIds) {
        try {
            return snapshotReader.findSnapshots(routeIds);
        } catch (DataAccessException exception) {
            throw new ServiceUnavailableException();
        }
    }

    @Override
    public Map<Long, List<BoardStop>> findStops(Collection<Long> routeVersionIds) {
        if (routeVersionIds.isEmpty()) {
            return Map.of();
        }
        try {
            Map<Long, List<BoardStop>> stops = new LinkedHashMap<>();
            for (RouteStopJpaEntity stop : routeStopRepository.findAllByRouteVersionIdIn(routeVersionIds)) {
                stops.computeIfAbsent(stop.routeVersionId(), ignored -> new ArrayList<>()).add(stop.toDomain());
            }
            return stops;
        } catch (DataAccessException exception) {
            throw new ServiceUnavailableException();
        }
    }

    @Override
    public Map<Long, List<StoredPrediction>> findRecordedPredictions(Map<Long, LocalDate> sourceDateByBatchId) {
        try {
            return recordReader.findPredictions(sourceDateByBatchId);
        } catch (DataAccessException exception) {
            throw new ServiceUnavailableException();
        }
    }

    @Override
    public Map<Long, List<BoardVehicleObservation>> findObservedVehicles(Map<Long, Long> routeVersionIdByBatchId) {
        if (routeVersionIdByBatchId.isEmpty()) {
            return Map.of();
        }
        try {
            Map<Long, List<BoardVehicleObservation>> vehicles = new LinkedHashMap<>();
            List<Object[]> rows = vehicleObservationRepository.findAllByBatchIdInAndRouteVersionIdIn(
                routeVersionIdByBatchId.keySet(), Set.copyOf(routeVersionIdByBatchId.values()));
            for (Object[] row : rows) {
                Long batchId = (Long) row[0];
                VehicleObservationJpaEntity observation = (VehicleObservationJpaEntity) row[1];
                if (observation.routeVersionId() != routeVersionIdByBatchId.get(batchId)) {
                    continue;
                }
                vehicles.computeIfAbsent(batchId, ignored -> new ArrayList<>()).add(observation.toDomain());
            }
            return vehicles;
        } catch (DataAccessException exception) {
            throw new ServiceUnavailableException();
        }
    }
}
