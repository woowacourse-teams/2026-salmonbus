package com.gustler.backend.forecasting.infrastructure.jdbc;

import static com.gustler.backend.forecasting.infrastructure.jdbc.MultiRowInsert.instant;
import static com.gustler.backend.forecasting.infrastructure.jdbc.MultiRowInsert.timestamp;

import com.gustler.backend.forecasting.api.forecast.PassClosed;
import com.gustler.backend.forecasting.application.forecast.StopPassStore;
import com.gustler.backend.forecasting.domain.forecast.SourceDates;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 닫힌 통과를 날짜 파티션 테이블 {@code stop_pass}에 저장하고 읽는다.
 *
 * <p>쓰기는 호출하는 쪽의 트랜잭션에 참여하며, 트랜잭션 없이 부르면 실패한다. 이미 있는 키는 건너뛴다.
 */
@Repository
public class JdbcStopPassStore implements StopPassStore {

    private static final List<String> COLUMNS = List.of(
            "source_date",
            "route_id",
            "trip_start_observation_id",
            "stop_order",
            "route_version_id",
            "vehicle_id",
            "chain_start_observation_id",
            "departure_date",
            "arrival_observation_id",
            "arrival_seats",
            "arrival_confirmed_at",
            "arrival_received_at",
            "first_observation_id",
            "first_confirmed_at",
            "last_observation_id",
            "last_seats",
            "last_confirmed_at",
            "last_received_at",
            "reached_known_seat_at",
            "closure_observation_id",
            "closure_batch_id",
            "closed_at"
    );

    private static final MultiRowInsert PASSES = new MultiRowInsert("stop_pass", COLUMNS);

    private final JdbcClient jdbc;

    public JdbcStopPassStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = false, propagation = Propagation.MANDATORY)
    public int save(List<PassClosed> passes) {
        return PASSES.insert(jdbc, passes, pass -> Arrays.asList(
                pass.sourceDate(),
                pass.routeId(),
                pass.tripStartObservationId(),
                pass.stopOrder(),
                pass.routeVersionId(),
                pass.vehicleId(),
                pass.chainStartObservationId(),
                pass.departureDate(),
                pass.arrivalObservationId(),
                pass.arrivalSeats(),
                timestamp(pass.arrivalConfirmedAt()),
                timestamp(pass.arrivalReceivedAt()),
                pass.firstObservationId(),
                timestamp(pass.firstConfirmedAt()),
                pass.lastObservationId(),
                pass.lastSeats(),
                timestamp(pass.lastConfirmedAt()),
                timestamp(pass.lastReceivedAt()),
                timestamp(pass.reachedKnownSeatAt()),
                pass.closureObservationId(),
                pass.closureBatchId(),
                timestamp(pass.closedAt())
        ));
    }

    @Override
    public List<PassClosed> findClosedBetween(final long routeId, Instant fromInclusive, Instant toExclusive) {
        return jdbc.sql("""
                SELECT /* salmonbus:stop_pass.find_between */ %s
                FROM stop_pass
                WHERE route_id = ? AND closed_at >= ? AND closed_at < ?
                ORDER BY closed_at, closure_batch_id, closure_observation_id, trip_start_observation_id, stop_order
                """.formatted(String.join(", ", COLUMNS)))
            .params(routeId, timestamp(fromInclusive), timestamp(toExclusive))
            .query((row, index) -> new PassClosed(
                    row.getObject("source_date", LocalDate.class),
                    row.getLong("route_id"),
                    row.getLong("trip_start_observation_id"),
                    row.getInt("stop_order"),
                    row.getLong("route_version_id"),
                    row.getString("vehicle_id"),
                    row.getLong("chain_start_observation_id"),
                    row.getObject("departure_date", LocalDate.class),
                    row.getLong("arrival_observation_id"),
                    row.getInt("arrival_seats"),
                    instant(row, "arrival_confirmed_at"),
                    instant(row, "arrival_received_at"),
                    row.getLong("first_observation_id"),
                    instant(row, "first_confirmed_at"),
                    row.getLong("last_observation_id"),
                    row.getInt("last_seats"),
                    instant(row, "last_confirmed_at"),
                    instant(row, "last_received_at"),
                    instant(row, "reached_known_seat_at"),
                    row.getLong("closure_observation_id"),
                    row.getLong("closure_batch_id"),
                    instant(row, "closed_at")
            ))
            .list();
    }

    /**
     * 닫힌 시각이 늦은 통과부터 보며 정답 줄이 있는 첫 통과에서 멈춘다. 통과는 {@code (route_id, closed_at)} 인덱스로, 정답은 기본 키의
     * 앞 열로 찾는다. 닫힌 시각 하한의 하루 전 날짜부터의 파티션만 읽는다. 나눔 날짜(닫은 배치 예약 시각의 날짜)는 닫힌 시각의 날짜보다
     * 늦지 않다.
     */
    @Override
    public Optional<Instant> findLastScoredClosedAt(final long routeId, Instant fromInclusive, Instant toExclusive) {
        return jdbc.sql("""
                SELECT /* salmonbus:stop_pass.find_last_scored */ pass.closed_at
                FROM stop_pass pass
                WHERE pass.route_id = ? AND pass.source_date >= ? AND pass.closed_at >= ? AND pass.closed_at < ?
                    AND EXISTS (
                        SELECT 1 FROM pass_label label
                        WHERE label.source_date = pass.source_date AND label.route_id = pass.route_id
                            AND label.trip_start_observation_id = pass.trip_start_observation_id
                            AND label.stop_order = pass.stop_order)
                ORDER BY pass.closed_at DESC
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
}
