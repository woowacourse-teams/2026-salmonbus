package com.gustler.backend.processor.persistence.jdbc;

import com.gustler.backend.processor.StopDemandCell;
import com.gustler.backend.processor.StopDemandGeneration;
import com.gustler.backend.processor.StopDemandHourlyTotals;
import com.gustler.backend.processor.StopDemandMeasurement;
import com.gustler.backend.processor.StopDemandStatistics;
import com.gustler.backend.processor.StopDemandStatisticsRepository;
import com.gustler.backend.processor.TimeSlot;
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

/**
 * 셀 통계를 SQL 로 직접 읽고 쓴다. JPA 엔티티를 두지 않는다.
 *
 * <p>재료가 되는 예보 · 관측 · 관측 판은 collector 가 소유한 표에 있지만 여기서 직접 조인한다.
 * collector 코드를 부르면 패키지 경계 검사가 막는다.
 */
@Repository
public class JdbcStopDemandStatisticsRepository implements StopDemandStatisticsRepository {

    /** 집계가 한 번도 안 돈 상태. 행이 하나도 없는 것이 곧 이 상태다. */
    private static final int NO_GENERATION_YET = 0;

    /**
     * 그 계산 규칙 판의 세대 번호와, 그 세대에서 고른 시간대의 셀들. 정류장 순번 오름차순이다.
     *
     * <p>승차할 수 없는 정류장에는 셀이 없어서 순번이 건너뛴다. 행 수는 판본 정류장 수와 다르다.
     *
     * <p><b>세대 번호를 시간대로 안 거른다.</b> 한 세대는 세 시간대를 한꺼번에 내는데,
     * 그중 한 시간대에 라벨이 아직 안 쌓여 셀이 없을 수 있다. 그 자리를 시간대로 걸러 세면
     * "N세대인데 이 시간대는 비었다" 가 "세대가 아예 없다" 와 같은 0으로 나온다.
     *
     * <p>세대는 관측 시각까지의 자료로 낸 것 중 가장 최근 것을 고른다. 지금 최신 세대를 쓰면
     * 밀린 batch 를 뒤늦게 처리할 때 그 관측 시각보다 뒤의 라벨이 들어간 셀을 읽는다.
     *
     * <p>그래서 세대 번호를 먼저 잡고 셀을 LEFT JOIN 한다. 셀이 없어도 행 하나는 나오고
     * 그 행의 stop_order 가 비어 있다. 두 값을 한 조회에서 같이 가져오는 것이라
     * 그 사이에 세대가 교체될 자리도 없다.
     */
    private static final String SELECT_CELLS = """
        SELECT generation.revision,
               cell.stop_order,
               cell.average_fill_rate,
               cell.average_net_boarding_rate,
               cell.sample_count,
               cell.day_count
        FROM (
            SELECT COALESCE(MAX(revision), 0) AS revision
            FROM stop_demand_statistics
            WHERE route_version_id = :routeVersionId
              AND calculation_version = :calculationVersion
              AND data_until <= :observedAt
        ) generation
        LEFT JOIN stop_demand_statistics cell
               ON cell.route_version_id = :routeVersionId
              AND cell.calculation_version = :calculationVersion
              AND cell.time_slot = :timeSlot
              AND cell.revision = generation.revision
        ORDER BY cell.stop_order
        """;

    /**
     * 지금 세대 번호.
     *
     * <p>시간대를 안 본다. 한 판이 세 시간대를 같은 번호로 한꺼번에 쓰기 때문에 시간대별로 세면
     * 라벨이 한 시간대에만 있던 판에서 번호가 갈린다.
     */
    private static final String SELECT_CURRENT_REVISION = """
        SELECT COALESCE(MAX(revision), 0)
        FROM stop_demand_statistics
        WHERE route_version_id = :routeVersionId
          AND calculation_version = :calculationVersion
        """;

    /**
     * 회수된 라벨을 (정류장, 한 시각) 으로 묶어 더한다.
     *
     * <p>평균이 아니라 합을 낸다. 순승차 비율이 평균의 비율이지 비율의 평균이 아니라서,
     * 여기서 미리 나누면 접을 때 값이 달라진다. 접는 것은 StopDemandAggregator 가 한다.
     *
     * <p>정원은 열이 아니라 관측에서 유도한다. <b>그 차량이 보여 준 최대 잔여석</b>이 정원이다.
     * 아무도 안 탄 순간이 관측에 한 번이라도 잡히면 그 잔여석이 곧 좌석 수다.
     * 최소 1 로 막는 것은 잔여석이 내내 0 이던 차량에서 0 으로 나누지 않으려는 것이다.
     *
     * <p><b>지평 1 짜리 예보만 센다.</b> 한 통과 사건이 지평 열둘에 걸쳐 예보 행 열둘을 만들어서,
     * 안 거르면 같은 사건이 열두 번 세어진다.
     *
     * <p>시각은 UTC 로 못박아 자른다. date_trunc 를 timestamptz 에 그냥 걸면 세션 타임존을 따라간다.
     * 한국시는 정각 오프셋이라 UTC 로 잘라도 시각 경계가 같고, 이렇게 두면 세션 설정에 안 흔들린다.
     * <b>시간대와 날짜 판정은 SQL 이 안 한다.</b> 그 규칙은 TimeSlot 과 주입받은 시계가 가진다.
     * 여기서 같이 판정하면 규칙이 두 벌이 되고 어긋나는 순간을 아무도 못 잡는다.
     *
     * <p>도착 시각은 도착 관측이 딸린 판의 response_received_at 이다. 관측 시각의 권위가 거기 있다.
     * 라벨은 응답을 받은 판에서만 나와서 그 시각이 비지 않는다.
     *
     * <p><b>정원도 기준 시각에 걸린다.</b> 정원이 그 차량이 보여 준 최대 잔여석이라, 기준 시각 뒤에 더 큰
     * 잔여석이 들어오면 예전 라벨의 분모가 달라진다. 그러면 같은 기준 시각으로 다시 돌려도 다른 값이 나와서
     * 세대를 고정한다는 말이 성립하지 않는다. 그래서 정원을 세는 관측도 판을 조인해 같은 시각으로 자른다.
     */
    private static final String SELECT_HOURLY_TOTALS = """
        WITH vehicle_capacity AS (
            SELECT observation.vehicle_id,
                   GREATEST(MAX(observation.remaining_seats), 1) AS capacity
            FROM vehicle_observation observation
            JOIN observation_batch capacity_batch
              ON capacity_batch.id = observation.observation_batch_id
            WHERE observation.route_version_id = :routeVersionId
              AND observation.vehicle_id IS NOT NULL
              AND observation.remaining_seats IS NOT NULL
              AND capacity_batch.response_received_at <= :dataUntil
            GROUP BY observation.vehicle_id
        )
        SELECT forecast.target_stop_order AS stop_order,
               date_trunc('hour', arrival_batch.response_received_at AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'
                   AS arrived_hour_start,
               SUM(1 - forecast.seats_on_arrival::double precision / vehicle_capacity.capacity)
                   AS fill_rate_total,
               SUM(prediction.remaining_seats - forecast.seats_on_arrival)::double precision
                   AS net_boarding_total,
               SUM(vehicle_capacity.capacity)::double precision AS capacity_total,
               COUNT(*) AS sample_count
        FROM seat_forecast forecast
        JOIN route_stop target_stop
          ON target_stop.route_version_id = forecast.route_version_id
         AND target_stop.stop_order = forecast.target_stop_order
        JOIN vehicle_observation prediction
          ON prediction.id = forecast.vehicle_observation_id
        JOIN vehicle_observation arrival
          ON arrival.id = forecast.arrival_observation_id
        JOIN observation_batch arrival_batch
          ON arrival_batch.id = arrival.observation_batch_id
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

    private static final String SELECT_SEEDED_HOURLY_TOTALS = """
        WITH active_seed AS (
            SELECT final_cutover_at
            FROM stop_demand_seed_import
            WHERE status = 'APPLIED' AND calculation_version = :calculationVersion
        ),
        vehicle_capacity AS (
            SELECT observation.vehicle_id,
                   GREATEST(MAX(observation.remaining_seats), 1) AS capacity
            FROM vehicle_observation observation
            JOIN observation_batch capacity_batch
              ON capacity_batch.id = observation.observation_batch_id
            WHERE observation.route_version_id = :routeVersionId
              AND observation.vehicle_id IS NOT NULL
              AND observation.remaining_seats IS NOT NULL
              AND capacity_batch.response_received_at <= :dataUntil
            GROUP BY observation.vehicle_id
        ),
        live_hourly AS (
            SELECT forecast.target_stop_order AS stop_order,
                   date_trunc('hour', arrival_batch.response_received_at AT TIME ZONE 'UTC')
                       AT TIME ZONE 'UTC' AS arrived_hour_start,
                   SUM(1 - forecast.seats_on_arrival::double precision / vehicle_capacity.capacity)
                       AS fill_rate_total,
                   SUM(prediction.remaining_seats - forecast.seats_on_arrival)::double precision
                       AS net_boarding_total,
                   SUM(vehicle_capacity.capacity)::double precision AS capacity_total,
                   COUNT(*) AS sample_count
            FROM seat_forecast forecast
            JOIN route_stop target_stop
              ON target_stop.route_version_id = forecast.route_version_id
             AND target_stop.stop_order = forecast.target_stop_order
            JOIN vehicle_observation prediction
              ON prediction.id = forecast.vehicle_observation_id
            JOIN observation_batch prediction_batch
              ON prediction_batch.id = prediction.observation_batch_id
            JOIN vehicle_observation arrival
              ON arrival.id = forecast.arrival_observation_id
            JOIN observation_batch arrival_batch
              ON arrival_batch.id = arrival.observation_batch_id
            JOIN vehicle_capacity
              ON vehicle_capacity.vehicle_id = prediction.vehicle_id
            CROSS JOIN active_seed
            WHERE forecast.route_version_id = :routeVersionId
              AND forecast.scoring_state = 'SETTLED'
              AND forecast.stops_to_target = 1
              AND forecast.scored_at <= :dataUntil
              AND prediction_batch.response_received_at >= active_seed.final_cutover_at
              AND target_stop.boarding_allowed = true
              AND prediction.remaining_seats IS NOT NULL
            GROUP BY forecast.target_stop_order, arrived_hour_start
        ),
        combined AS (
            SELECT seed.stop_order, seed.arrival_hour_start AS arrived_hour_start,
                   seed.fill_rate_total, seed.net_boarding_total,
                   seed.capacity_total, seed.sample_count
            FROM active_stop_demand_seed_hourly_total seed
            WHERE seed.route_version_id = :routeVersionId
              AND seed.calculation_version = :calculationVersion
            UNION ALL
            SELECT stop_order, arrived_hour_start, fill_rate_total,
                   net_boarding_total, capacity_total, sample_count
            FROM live_hourly
        )
        SELECT stop_order, arrived_hour_start,
               SUM(fill_rate_total) AS fill_rate_total,
               SUM(net_boarding_total) AS net_boarding_total,
               SUM(capacity_total) AS capacity_total,
               SUM(sample_count)::integer AS sample_count
        FROM combined
        GROUP BY stop_order, arrived_hour_start
        ORDER BY stop_order, arrived_hour_start
        """;

    private static final String SEED_SCHEMA = "public.stop_demand_seed_import";
    private static final String CURRENT_CALCULATION_VERSION = "observed-max-capacity-v1";

    /** 셀 한 줄. 세대 번호와 기준 시각과 계산 시각은 한 세대의 모든 행에 같은 값이 들어간다. */
    private static final String INSERT_CELL = """
        INSERT INTO stop_demand_statistics (
            route_version_id, stop_order, time_slot, calculation_version, revision,
            average_fill_rate, average_net_boarding_rate, sample_count, day_count,
            data_until, computed_at
        ) VALUES (
            :routeVersionId, :stopOrder, :timeSlot, :calculationVersion, :revision,
            :averageFillRate, :averageNetBoardingRate, :sampleCount, :dayCount,
            :dataUntil, :computedAt
        )
        """;

    private final JdbcClient jdbcClient;
    private volatile boolean seedSchemaPresent;

    public JdbcStopDemandStatisticsRepository(
        JdbcClient jdbcClient
    ) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * 한 세대의 셀 전부.
     *
     * <p>세대가 하나도 없으면 세대 번호 0 에 빈 셀 목록으로 답한다. 예외로 알리지 않는다.
     * 개편 직후 새 판본이 실제로 그 상태이고, 그때는 이웃 폴백만 돈다.
     *
     * <p><b>세대는 있는데 그 시간대의 셀만 없는 것은 다른 상태다.</b> 그때는 세대 번호가 그대로
     * 나오고 셀 목록만 비어 있다. 예보 행에 어느 세대를 보고 냈는지가 남아야 나중에 따질 수 있다.
     */
    @Override
    public StopDemandStatistics readAsOf(
        final long routeVersionId,
        TimeSlot timeSlot,
        String calculationVersion,
        Instant observedAt
    ) {
        List<CellOfGeneration> rows = jdbcClient.sql(SELECT_CELLS)
            .param("routeVersionId", routeVersionId)
            .param("timeSlot", storedTimeSlotOf(timeSlot))
            .param("calculationVersion", calculationVersion)
            .param("observedAt", offsetOf(observedAt))
            .query((resultSet, rowNumber) -> new CellOfGeneration(
                resultSet.getInt("revision"),
                cellOf(resultSet)))
            .list();
        List<StopDemandCell> cells = new ArrayList<>();
        for (CellOfGeneration row : rows) {
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
        boolean seeded = hasAppliedAggregateSeed();
        String query = seeded ? SELECT_SEEDED_HOURLY_TOTALS : SELECT_HOURLY_TOTALS;
        JdbcClient.StatementSpec statement = jdbcClient.sql(query)
            .param("routeVersionId", routeVersionId)
            .param("dataUntil", offsetOf(dataUntil));
        if (seeded) {
            statement = statement.param("calculationVersion", CURRENT_CALCULATION_VERSION);
        }
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

    private boolean hasAppliedAggregateSeed() {
        if (!seedSchemaPresent) {
            seedSchemaPresent = jdbcClient.sql("SELECT to_regclass(?) IS NOT NULL")
                .param(SEED_SCHEMA)
                .query(Boolean.class)
                .single();
        }
        return seedSchemaPresent && jdbcClient.sql("""
            SELECT EXISTS (
                SELECT 1 FROM stop_demand_seed_import
                WHERE status = 'APPLIED' AND calculation_version = ?)
            """)
            .param(CURRENT_CALCULATION_VERSION)
            .query(Boolean.class)
            .single();
    }

    /**
     * 한 세대를 더한다. 옛 세대는 안 지운다.
     *
     * <p><b>transaction 은 세대 하나를 다 넣을 때까지 아무도 못 읽게 하려고 건다.</b> 없으면 셀마다
     * 자동 커밋되고, 첫 행이 커밋되는 순간 {@link #readAsOf} 가 그 revision 을 가장 최근 세대로 잡는다.
     * 그러면 몇 줄만 들어간 세대로 z 모집단이 만들어져 살아 있는 셀의 z 값이 통째로 밀린다.
     *
     * <p>부르는 배치가 아니라 여기에 건다. 배치가 자기 메서드를 부르는 구조라 거기서 걸면
     * 프록시를 안 거쳐 transaction 이 안 열린다.
     */
    @Override
    @Transactional
    public void append(
        StopDemandGeneration generation
    ) {
        for (StopDemandMeasurement measurement : generation.measurements()) {
            insertCell(generation, measurement);
        }
    }

    private void insertCell(
        StopDemandGeneration generation,
        StopDemandMeasurement measurement
    ) {
        StopDemandCell cell = measurement.cell();
        jdbcClient.sql(INSERT_CELL)
            .param("routeVersionId", generation.routeVersionId())
            .param("stopOrder", cell.stopOrder())
            .param("timeSlot", storedTimeSlotOf(measurement.timeSlot()))
            .param("calculationVersion", generation.calculationVersion())
            .param("revision", generation.revision())
            .param("averageFillRate", cell.averageFillRate())
            .param("averageNetBoardingRate", cell.averageNetBoardingRate())
            .param("sampleCount", cell.sampleCount())
            .param("dayCount", cell.dayCount())
            .param("dataUntil", offsetOf(generation.dataUntil()))
            .param("computedAt", offsetOf(generation.computedAt()))
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

    /** LEFT JOIN 이라 행은 늘 하나 이상이다. 비어 있으면 조회가 아예 안 돈 것이다. */
    private static int revisionOf(
        List<CellOfGeneration> rows
    ) {
        if (rows.isEmpty()) {
            return NO_GENERATION_YET;
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

    /** 읽어 온 셀 하나와 그 셀이 속한 세대 번호. 한 세대의 행은 세대 번호가 다 같다. */
    private record CellOfGeneration(
        int revision,
        StopDemandCell cell
    ) {
    }
}
