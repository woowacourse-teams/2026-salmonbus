package com.gustler.backend.api.board.infrastructure;

import com.gustler.backend.api.board.ForecastSourceSetting;
import com.gustler.backend.api.board.application.BoardQueryRepository;
import com.gustler.backend.api.board.application.BoardSnapshot;
import com.gustler.backend.api.board.application.BoardVehicleObservation;
import com.gustler.backend.api.board.application.StoredPrediction;
import com.gustler.backend.api.board.domain.BoardStop;
import com.gustler.backend.api.board.infrastructure.jdbc.JdbcBoardSnapshotReader;
import com.gustler.backend.api.board.infrastructure.jdbc.JdbcForecastRecordReader;
import com.gustler.backend.api.board.infrastructure.jpa.JpaBoardQueryRepository;
import com.gustler.backend.api.error.ServiceUnavailableException;
import com.gustler.backend.api.route.RouteId;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Repository;

/**
 * 설정한 예보 출처({@code board.forecast-source})에 맞춰 탑승판 조회를 나눈다.
 *
 * <p>{@link ForecastSourceSetting#LEGACY}면 모든 조회를 {@link JpaBoardQueryRepository}에 그대로 넘긴다. 이때 동작은 이
 * 클래스가 없을 때와 같다. {@link ForecastSourceSetting#RECORD}·{@link ForecastSourceSetting#AUTO}면 스냅샷을 출처를 고르는
 * 단일 질의로 읽고({@code board.snapshot.single-query.enabled}와 관계없다), 새 예보 기록 출처의 예측은
 * {@link JdbcForecastRecordReader}로 읽는다. 정류장·차량·옛 예보는 출처와 관계없이 넘긴다.
 * 설정 값이 세 이름 중 하나가 아니면 빈을 만들지 못해 앱이 뜨지 않는다.
 */
@Primary
@Repository
public class ForecastSourceBoardQueryRepository implements BoardQueryRepository {
    private final JpaBoardQueryRepository legacy;
    private final JdbcBoardSnapshotReader snapshotReader;
    private final JdbcForecastRecordReader recordReader;
    private final ForecastSourceSetting source;

    @Autowired
    public ForecastSourceBoardQueryRepository(
        JpaBoardQueryRepository legacy,
        JdbcBoardSnapshotReader snapshotReader,
        JdbcForecastRecordReader recordReader,
        @Value("${board.forecast-source:legacy}") String source
    ) {
        this(legacy, snapshotReader, recordReader, ForecastSourceSetting.of(source));
    }

    /**
     * 출처를 값으로 받아 만든다.
     */
    public ForecastSourceBoardQueryRepository(
        JpaBoardQueryRepository legacy,
        JdbcBoardSnapshotReader snapshotReader,
        JdbcForecastRecordReader recordReader,
        ForecastSourceSetting source
    ) {
        this.legacy = legacy;
        this.snapshotReader = snapshotReader;
        this.recordReader = recordReader;
        this.source = source;
    }

    @Override
    public Optional<BoardSnapshot> findSnapshot(RouteId routeId) {
        if (source == ForecastSourceSetting.LEGACY) {
            return legacy.findSnapshot(routeId);
        }
        try {
            return snapshotReader.findSnapshot(routeId, source);
        } catch (DataAccessException exception) {
            throw new ServiceUnavailableException();
        }
    }

    @Override
    public List<BoardSnapshot> findSnapshots(Collection<RouteId> routeIds) {
        if (source == ForecastSourceSetting.LEGACY) {
            return legacy.findSnapshots(routeIds);
        }
        try {
            return snapshotReader.findSnapshots(routeIds, source);
        } catch (DataAccessException exception) {
            throw new ServiceUnavailableException();
        }
    }

    @Override
    public List<BoardStop> findStops(final long routeVersionId) {
        return legacy.findStops(routeVersionId);
    }

    @Override
    public Map<Long, List<BoardStop>> findStops(Collection<Long> routeVersionIds) {
        return legacy.findStops(routeVersionIds);
    }

    @Override
    public List<StoredPrediction> findPredictions(final long observationBatchId) {
        return legacy.findPredictions(observationBatchId);
    }

    @Override
    public Map<Long, List<StoredPrediction>> findPredictions(Collection<Long> observationBatchIds) {
        return legacy.findPredictions(observationBatchIds);
    }

    @Override
    public List<StoredPrediction> findRecordedPredictions(final long observationBatchId, LocalDate sourceDate) {
        return findRecordedPredictions(Map.of(observationBatchId, sourceDate))
            .getOrDefault(observationBatchId, List.of());
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
    public List<BoardVehicleObservation> findObservedVehicles(
        final long observationBatchId,
        final long routeVersionId
    ) {
        return legacy.findObservedVehicles(observationBatchId, routeVersionId);
    }

    @Override
    public Map<Long, List<BoardVehicleObservation>> findObservedVehicles(Map<Long, Long> routeVersionIdByBatchId) {
        return legacy.findObservedVehicles(routeVersionIdByBatchId);
    }
}
