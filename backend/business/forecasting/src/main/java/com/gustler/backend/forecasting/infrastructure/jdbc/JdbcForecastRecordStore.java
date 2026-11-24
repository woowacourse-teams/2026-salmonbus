package com.gustler.backend.forecasting.infrastructure.jdbc;

import static com.gustler.backend.forecasting.infrastructure.jdbc.MultiRowInsert.doubles;
import static com.gustler.backend.forecasting.infrastructure.jdbc.MultiRowInsert.instant;
import static com.gustler.backend.forecasting.infrastructure.jdbc.MultiRowInsert.integers;
import static com.gustler.backend.forecasting.infrastructure.jdbc.MultiRowInsert.nullableLong;
import static com.gustler.backend.forecasting.infrastructure.jdbc.MultiRowInsert.timestamp;

import com.gustler.backend.forecasting.api.forecast.ForecastOutcome;
import com.gustler.backend.forecasting.api.forecast.ForecastPublished;
import com.gustler.backend.forecasting.api.forecast.ObservationBatchSeen;
import com.gustler.backend.forecasting.application.forecast.ForecastRecordStore;
import com.gustler.backend.forecasting.application.forecast.ForecastRun;
import com.gustler.backend.forecasting.application.forecast.RecentForecastRoutes;
import com.gustler.backend.forecasting.application.forecast.RecordedBatch;
import com.gustler.backend.forecasting.domain.forecast.SourceDates;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예보 실행과 버스별 예보 기록을 날짜 파티션 테이블에 저장하고 읽는다.
 *
 * <p>쓰기는 호출하는 쪽의 트랜잭션에 참여하며, 트랜잭션 없이 부르면 실패한다. 실행 줄과 기록 줄 모두 이미 있는 키를 건너뛰므로
 * 실행 줄이 이미 있어도 빠진 기록 줄은 채운다. 날짜 파티션이 없으면 줄은 기본 파티션으로 들어간다. 실행 줄에는 같은 배치의 수집
 * 사건이 알려 주는 수집 구멍 여부({@code source_incomplete})와 하루 관측 상한에 더할 줄 수({@code counted_rows})를 적는다.
 */
@Repository
public class JdbcForecastRecordStore implements ForecastRecordStore, RecentForecastRoutes {

    private static final MultiRowInsert RUNS = new MultiRowInsert("forecast_run", List.of(
            "source_date",
            "observation_batch_id",
            "route_id",
            "route_version_id",
            "received_at",
            "confirmed_at",
            "model_deployment_id",
            "outcome",
            "prediction_count",
            "source_incomplete",
            "counted_rows"
    ));

    private static final MultiRowInsert RECORDS = new MultiRowInsert("forecast_record", List.of(
            "source_date",
            "vehicle_observation_id",
            "observation_batch_id",
            "source_row_number",
            "route_id",
            "route_version_id",
            "vehicle_id",
            "passed_stop_order",
            "trip_start_observation_id",
            "chain_start_observation_id",
            "down",
            "capacity",
            "extended",
            "target_stop_orders",
            "expected_seats",
            "full_probabilities"
    ));

    private static final String RECORD_COLUMNS = """
        record.vehicle_observation_id, record.source_row_number, record.vehicle_id, record.passed_stop_order,
        record.trip_start_observation_id, record.chain_start_observation_id, record.down, record.capacity,
        record.extended, record.target_stop_orders, record.expected_seats, record.full_probabilities
        """;

    private final JdbcClient jdbc;

    public JdbcForecastRecordStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = false, propagation = Propagation.MANDATORY)
    public boolean save(ForecastPublished published, ObservationBatchSeen seen) {
        if (seen.observationBatchId() != published.observationBatchId()) {
            throw new IllegalArgumentException("예보 사건과 수집 사건의 배치가 다르다: %d, %d"
                .formatted(published.observationBatchId(), seen.observationBatchId()));
        }
        final int runs = RUNS.insert(jdbc, List.of(published), run -> Arrays.asList(
                run.sourceDate(),
                run.observationBatchId(),
                run.routeId(),
                run.routeVersionId(),
                timestamp(run.receivedAt()),
                timestamp(run.confirmedAt()),
                run.modelDeploymentId(),
                run.outcome().name(),
                run.predictionCount(),
                seen.incomplete(),
                seen.countedRows()
        ));
        RECORDS.insert(jdbc, published.vehicles(), vehicle -> Arrays.asList(
                published.sourceDate(),
                vehicle.vehicleObservationId(),
                published.observationBatchId(),
                vehicle.sourceRowNumber(),
                published.routeId(),
                published.routeVersionId(),
                vehicle.vehicleId(),
                vehicle.passedStopOrder(),
                vehicle.tripStartObservationId(),
                vehicle.chainStartObservationId(),
                vehicle.down(),
                vehicle.capacity(),
                vehicle.extended(),
                integers(vehicle.targetStopOrders()),
                doubles(vehicle.expectedSeats()),
                doubles(vehicle.fullProbabilities())
        ));
        return runs == 1;
    }

    @Override
    public List<ForecastPublished> findPublishedBetween(
        final long routeId,
        Instant fromInclusive,
        Instant toExclusive
    ) {
        Map<RunKey, RunRows> runs = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT /* salmonbus:forecast_run.find_between */ run.source_date, run.observation_batch_id,
                    run.route_id, run.route_version_id, run.received_at, run.confirmed_at, run.model_deployment_id,
                    run.outcome, run.prediction_count, record.vehicle_observation_id IS NOT NULL AS has_record, %s
                FROM forecast_run run
                LEFT JOIN forecast_record record
                    ON record.source_date = run.source_date AND record.observation_batch_id = run.observation_batch_id
                WHERE run.route_id = ? AND run.received_at >= ? AND run.received_at < ?
                ORDER BY run.received_at, run.observation_batch_id, record.source_row_number,
                    record.vehicle_observation_id
                """.formatted(RECORD_COLUMNS))
            .params(routeId, timestamp(fromInclusive), timestamp(toExclusive))
            .query(row -> {
                ForecastRun run = run(row);
                RunRows rows = runs.computeIfAbsent(new RunKey(
                    run.sourceDate(),
                    run.observationBatchId()
                ),
                    key -> new RunRows(run, new ArrayList<>()));
                if (row.getBoolean("has_record")) {
                    rows.vehicles().add(vehicle(row));
                }
            });
        return runs.values()
            .stream()
            .map(JdbcForecastRecordStore::published)
            .toList();
    }

    private static ForecastPublished published(RunRows rows) {
        ForecastRun run = rows.run();
        return new ForecastPublished(
                run.sourceDate(),
                run.observationBatchId(),
                run.routeId(),
                run.routeVersionId(),
                run.receivedAt(),
                run.confirmedAt(),
                run.modelDeploymentId(),
                run.outcome(),
                rows.vehicles()
        );
    }

    /** 수신 시각 하한의 하루 전 날짜부터의 파티션만 읽는다. */
    @Override
    public List<RecordedBatch> findRecordedBatchesBetween(
        final long routeId,
        Instant fromInclusive,
        Instant toExclusive
    ) {
        return jdbc.sql("""
                SELECT /* salmonbus:forecast_run.find_recorded */ observation_batch_id, route_id, received_at,
                    confirmed_at, outcome, source_incomplete, counted_rows
                FROM forecast_run
                WHERE route_id = ? AND source_date >= ? AND received_at >= ? AND received_at < ?
                ORDER BY received_at, observation_batch_id
                """)
            .params(
                    routeId,
                    SourceDates.of(fromInclusive).minusDays(1),
                    timestamp(fromInclusive),
                    timestamp(toExclusive)
            )
            .query((row, index) -> new RecordedBatch(
                    row.getLong("observation_batch_id"),
                    row.getLong("route_id"),
                    instant(row, "received_at"),
                    instant(row, "confirmed_at"),
                    ForecastOutcome.valueOf(row.getString("outcome")) == ForecastOutcome.LATE,
                    row.getBoolean("source_incomplete"),
                    row.getInt("counted_rows")
            ))
            .list();
    }

    /**
     * 늦게 수신한 실행부터 보며 미해결 줄이 있는 첫 실행에서 멈춘다. 실행은 {@code (route_id, received_at)} 인덱스로, 버스별 기록은 배치
     * 인덱스로, 미해결 줄은 기본 키의 앞 열로 찾는다. 수신 시각 하한의 하루 전 날짜부터의 파티션만 읽는다.
     */
    @Override
    public Optional<Instant> findLastUnresolvedConfirmedAt(
        final long routeId,
        Instant fromInclusive,
        Instant toExclusive
    ) {
        return jdbc.sql("""
                SELECT /* salmonbus:forecast_run.find_last_unresolved */ run.confirmed_at
                FROM forecast_run run
                WHERE run.route_id = ? AND run.source_date >= ? AND run.received_at >= ? AND run.received_at < ?
                    AND run.outcome = 'PUBLISHED'
                    AND EXISTS (
                        SELECT 1 FROM forecast_record record
                        JOIN forecast_unresolved closed ON closed.source_date = record.source_date
                            AND closed.vehicle_observation_id = record.vehicle_observation_id
                        WHERE record.source_date = run.source_date
                            AND record.observation_batch_id = run.observation_batch_id)
                ORDER BY run.received_at DESC, run.observation_batch_id DESC
                LIMIT 1
                """)
            .params(
                    routeId,
                    SourceDates.of(fromInclusive).minusDays(1),
                    timestamp(fromInclusive),
                    timestamp(toExclusive)
            )
            .query(OffsetDateTime.class)
            .optional()
            .map(OffsetDateTime::toInstant);
    }

    /** 수신 시각 하한의 하루 전 날짜부터의 파티션만 읽는다. 나눔 날짜(예약 시각의 날짜)는 수신 시각의 날짜보다 늦지 않다. */
    @Override
    public List<Long> routesPublishedSince(Instant fromInclusive) {
        return jdbc.sql("""
                SELECT /* salmonbus:forecast_run.recent_routes */ DISTINCT route_id
                FROM forecast_run
                WHERE source_date >= ? AND received_at >= ?
                ORDER BY route_id
                """)
            .params(SourceDates.of(fromInclusive).minusDays(1), timestamp(fromInclusive))
            .query(Long.class)
            .list();
    }

    private static ForecastRun run(ResultSet row) throws SQLException {
        return new ForecastRun(
                row.getObject("source_date", LocalDate.class),
                row.getLong("observation_batch_id"),
                row.getLong("route_id"),
                row.getLong("route_version_id"),
                instant(row, "received_at"),
                instant(row, "confirmed_at"),
                nullableLong(row, "model_deployment_id"),
                ForecastOutcome.valueOf(row.getString("outcome")),
                row.getInt("prediction_count")
        );
    }

    private static ForecastPublished.VehicleForecast vehicle(ResultSet row) throws SQLException {
        return new ForecastPublished.VehicleForecast(
                row.getLong("vehicle_observation_id"),
                row.getInt("source_row_number"),
                row.getString("vehicle_id"),
                row.getInt("passed_stop_order"),
                row.getLong("trip_start_observation_id"),
                row.getLong("chain_start_observation_id"),
                row.getBoolean("down"),
                row.getInt("capacity"),
                row.getBoolean("extended"),
                integers(row, "target_stop_orders"),
                doubles(row, "expected_seats"),
                doubles(row, "full_probabilities")
        );
    }

    private record RunKey(LocalDate sourceDate, long observationBatchId) {

    }

    private record RunRows(ForecastRun run, List<ForecastPublished.VehicleForecast> vehicles) {

    }
}
