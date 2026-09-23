package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsVersion;
import com.gustler.backend.forecasting.domain.statistics.StopDemandCell;
import com.gustler.backend.forecasting.domain.statistics.StopDemandHourlyTotals;
import com.gustler.backend.forecasting.domain.statistics.StopDemandMeasurement;
import com.gustler.backend.forecasting.domain.statistics.StopDemandStatistics;
import com.gustler.backend.forecasting.domain.statistics.StopDemandStatisticsRepository;
import com.gustler.backend.forecasting.domain.statistics.TimeSlot;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 통계 버전과 해당 정류장·시간대의 집계값을 함께 저장하고 조회한다. */
@Repository
public class JdbcStopDemandStatisticsRepository implements StopDemandStatisticsRepository {

    /** 아직 사용할 통계 버전이 없는 상태. */
    private static final int NO_STATISTICS_YET = 0;

    /**
     * 관측 시각까지의 자료로 계산한 최신 통계를 읽는다.
     *
     * <p>시간대에 해당하는 셀이 없어도 버전 번호는 유지한다. 버전 정보와 셀을 한 SQL로
     * 조회하므로, 읽는 도중 새 통계가 저장되어도 서로 다른 버전이 섞이지 않는다.
     */
    private static final String SELECT_CELLS = """
        SELECT version.revision,
               cell.stop_order,
               cell.average_fill_rate,
               cell.average_net_boarding_rate,
               cell.sample_count,
               cell.day_count
        FROM (
            SELECT COALESCE(MAX(revision), 0) AS revision
            FROM demand_statistics_version
            WHERE route_version_id = :routeVersionId
              AND calculation_version = :calculationVersion
              AND data_until <= :observedAt
              AND quality_revision = (
                  SELECT q.quality_revision FROM route_version v
                  JOIN route_data_quality q ON q.route_id = v.route_id WHERE v.id = :routeVersionId)
              AND NOT EXISTS (SELECT 1 FROM trip_quality_rebuild r
                              WHERE r.route_version_id = :routeVersionId AND NOT r.completed)
        ) version
        LEFT JOIN stop_demand_statistics cell
               ON cell.route_version_id = :routeVersionId
              AND cell.calculation_version = :calculationVersion
              AND cell.time_slot = :timeSlot
              AND cell.revision = version.revision
        ORDER BY cell.stop_order
        """;

    /** 품질 판정이 바뀌어 조회 대상에서 제외된 통계도 번호 할당 이력에는 포함한다. */
    private static final String SELECT_CURRENT_REVISION = """
        SELECT COALESCE(MAX(revision), 0)
        FROM demand_statistics_version
        WHERE route_version_id = :routeVersionId
          AND calculation_version = :calculationVersion
        """;

    /**
     * 평가가 완료된 예보를 정류장과 도착 시간별로 집계한다.
     *
     * <p>같은 도착을 중복해서 세지 않도록 한 정류장 앞의 예보만 사용한다. 정원은 기준 시각까지
     * 확인한 차량별 최대 잔여석으로 계산하고, 집계 대상은 현재 품질 조건을 통과한 자료로 제한한다.
     * 도착 시각은 평가할 때 보관한 값을 사용한다. 날짜와 시간대 분류는 도메인 집계기가 담당한다.
     */
    private static final String SELECT_HOURLY_TOTALS = """
        WITH vehicle_capacity AS (
            SELECT observation.vehicle_id,
                   GREATEST(MAX(observation.remaining_seats), 1) AS capacity
            FROM forecast_eligible_observation observation
            JOIN observation_batch capacity_batch
              ON capacity_batch.id = observation.observation_batch_id
            WHERE observation.route_version_id = :routeVersionId
              AND observation.vehicle_id IS NOT NULL
              AND observation.remaining_seats IS NOT NULL
              AND capacity_batch.response_received_at <= :dataUntil
            GROUP BY observation.vehicle_id
        )
        SELECT forecast.target_stop_order AS stop_order,
               date_trunc('hour', evaluation.arrived_at AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'
                   AS arrived_hour_start,
               SUM(1 - forecast.seats_on_arrival::double precision / vehicle_capacity.capacity)
                   AS fill_rate_total,
               SUM(prediction.remaining_seats - forecast.seats_on_arrival)::double precision
                   AS net_boarding_total,
               SUM(vehicle_capacity.capacity)::double precision AS capacity_total,
               COUNT(*) AS sample_count
        FROM quality_eligible_seat_forecast forecast
        JOIN route_stop target_stop
          ON target_stop.route_version_id = forecast.route_version_id
         AND target_stop.stop_order = forecast.target_stop_order
        JOIN vehicle_observation prediction
          ON prediction.id = forecast.vehicle_observation_id
        JOIN forecast_evaluation evaluation
          ON evaluation.vehicle_observation_id = forecast.vehicle_observation_id
         AND evaluation.target_stop_order = forecast.target_stop_order
        JOIN vehicle_capacity
          ON vehicle_capacity.vehicle_id = prediction.vehicle_id
        WHERE forecast.route_version_id = :routeVersionId
          AND forecast.scoring_state = 'SETTLED'
          AND forecast.stops_to_target = 1
          AND forecast.scored_at <= :dataUntil
          AND target_stop.boarding_allowed = true
          AND prediction.remaining_seats IS NOT NULL
        GROUP BY forecast.target_stop_order, arrived_hour_start
        ORDER BY forecast.target_stop_order, arrived_hour_start
        """;


    private static final String INSERT_VERSION = """
        INSERT INTO demand_statistics_version (
            route_version_id, calculation_version, revision, data_until, computed_at,
            quality_revision, cell_count, input_checkpoint
        ) VALUES (
            :routeVersionId, :calculationVersion, :revision, :dataUntil, :computedAt,
            :qualityRevision, :cellCount, NULL
        )
        """;

    /** 같은 버전에 속한 통계는 기준 시각과 품질 버전이 같다. */
    private static final String INSERT_CELL = """
        INSERT INTO stop_demand_statistics (
            route_version_id, stop_order, time_slot, calculation_version, revision,
            average_fill_rate, average_net_boarding_rate, sample_count, day_count,
            data_until, computed_at, quality_revision
        ) VALUES (
            :routeVersionId, :stopOrder, :timeSlot, :calculationVersion, :revision,
            :averageFillRate, :averageNetBoardingRate, :sampleCount, :dayCount,
            :dataUntil, :computedAt, :qualityRevision
        )
        """;

    private final JdbcClient jdbcClient;
    private final RouteDataQualityAccess qualityAccess;

    public JdbcStopDemandStatisticsRepository(
        JdbcClient jdbcClient,
        RouteDataQualityAccess qualityAccess
    ) {
        this.jdbcClient = jdbcClient;
        this.qualityAccess = qualityAccess;
    }

    /** 사용할 통계가 없으면 버전 0과 빈 목록을 반환한다. */
    @Override
    public StopDemandStatistics readAsOf(
        final long routeVersionId,
        TimeSlot timeSlot,
        String calculationVersion,
        Instant observedAt
    ) {
        List<VersionedCell> rows = jdbcClient.sql(SELECT_CELLS)
            .param("routeVersionId", routeVersionId)
            .param("timeSlot", storedTimeSlotOf(timeSlot))
            .param("calculationVersion", calculationVersion)
            .param("observedAt", offsetOf(observedAt))
            .query((resultSet, rowNumber) -> new VersionedCell(
                resultSet.getInt("revision"),
                cellOf(resultSet)))
            .list();
        List<StopDemandCell> cells = new ArrayList<>();
        for (VersionedCell row : rows) {
            if (row.cell() != null) {
                cells.add(row.cell());
            }
        }
        return new StopDemandStatistics(routeVersionId, timeSlot, revisionOf(rows), cells);
    }

    @Override
    public int currentRevision(
        final long routeVersionId,
        String calculationVersion
    ) {
        return jdbcClient.sql(SELECT_CURRENT_REVISION)
            .param("routeVersionId", routeVersionId)
            .param("calculationVersion", calculationVersion)
            .query(Integer.class)
            .single();
    }

    @Override
    public List<StopDemandHourlyTotals> readHourlyTotals(
        final long routeVersionId,
        Instant dataUntil
    ) {
        // 호출한 응용 서비스의 트랜잭션이 끝날 때까지 품질 변경과 다른 통계 생성을 막는다.
        qualityAccess.lock(routeVersionId);
        JdbcClient.StatementSpec statement = jdbcClient.sql(SELECT_HOURLY_TOTALS)
            .param("routeVersionId", routeVersionId)
            .param("dataUntil", offsetOf(dataUntil));
        return statement
            .query((resultSet, rowNumber) -> new StopDemandHourlyTotals(
                resultSet.getInt("stop_order"),
                instantOf(resultSet.getObject("arrived_hour_start", OffsetDateTime.class)),
                resultSet.getDouble("fill_rate_total"),
                resultSet.getDouble("net_boarding_total"),
                resultSet.getDouble("capacity_total"),
                resultSet.getInt("sample_count")))
            .list();
    }

    /** 버전 정보와 모든 셀을 같은 트랜잭션으로 저장해 일부만 공개되는 것을 막는다. */
    @Override
    @Transactional
    public void append(
        DemandStatisticsVersion version
    ) {
        final long qualityRevision = qualityAccess.lock(version.routeVersionId());
        jdbcClient.sql(INSERT_VERSION)
            .param("routeVersionId", version.routeVersionId())
            .param("calculationVersion", version.calculationVersion())
            .param("revision", version.revision())
            .param("dataUntil", offsetOf(version.dataUntil()))
            .param("computedAt", offsetOf(version.computedAt()))
            .param("qualityRevision", qualityRevision)
            .param("cellCount", version.measurements().size())
            .update();
        for (StopDemandMeasurement measurement : version.measurements()) {
            insertCell(version, measurement, qualityRevision);
        }
    }

    private void insertCell(
        DemandStatisticsVersion version,
        StopDemandMeasurement measurement,
        final long qualityRevision
    ) {
        StopDemandCell cell = measurement.cell();
        jdbcClient.sql(INSERT_CELL)
            .param("routeVersionId", version.routeVersionId())
            .param("stopOrder", cell.stopOrder())
            .param("timeSlot", storedTimeSlotOf(measurement.timeSlot()))
            .param("calculationVersion", version.calculationVersion())
            .param("revision", version.revision())
            .param("averageFillRate", cell.averageFillRate())
            .param("averageNetBoardingRate", cell.averageNetBoardingRate())
            .param("sampleCount", cell.sampleCount())
            .param("dayCount", cell.dayCount())
            .param("dataUntil", offsetOf(version.dataUntil()))
            .param("computedAt", offsetOf(version.computedAt()))
            .param("qualityRevision", qualityRevision)
            .update();
    }

    /** 그 시간대에 셀이 없으면 LEFT JOIN 이 빈 자리를 준다. 그때는 셀이 없다고 답한다. */
    private static StopDemandCell cellOf(
        ResultSet resultSet
    ) throws SQLException {
        Integer stopOrder = resultSet.getObject("stop_order", Integer.class);
        if (stopOrder == null) {
            return null;
        }
        return new StopDemandCell(
            stopOrder,
            resultSet.getDouble("average_fill_rate"),
            resultSet.getDouble("average_net_boarding_rate"),
            resultSet.getInt("sample_count"),
            resultSet.getInt("day_count"));
    }

    /** 조회 결과에 포함된 버전 번호를 반환한다. */
    private static int revisionOf(
        List<VersionedCell> rows
    ) {
        if (rows.isEmpty()) {
            return NO_STATISTICS_YET;
        }
        return rows.getFirst().revision();
    }

    /**
     * 저장하는 시간대 값. V3 의 열 주석이 morning · evening · other 를 적고 있다.
     *
     * <p>Locale.ROOT 로 내린다. 터키어 로캘에서는 대문자 I 가 점 없는 소문자로 내려가서
     * 기본 로캘을 따르면 배포 지역에 따라 저장되는 글자가 달라진다.
     */
    private static String storedTimeSlotOf(
        TimeSlot timeSlot
    ) {
        return timeSlot.name().toLowerCase(Locale.ROOT);
    }

    private static Instant instantOf(
        OffsetDateTime timestamp
    ) {
        return timestamp.toInstant();
    }

    private static OffsetDateTime offsetOf(
        Instant timestamp
    ) {
        return timestamp.atOffset(ZoneOffset.UTC);
    }

    /** 통계 버전 번호와 해당 버전에 속한 셀. */
    private record VersionedCell(
        int revision,
        StopDemandCell cell
    ) {
    }
}
