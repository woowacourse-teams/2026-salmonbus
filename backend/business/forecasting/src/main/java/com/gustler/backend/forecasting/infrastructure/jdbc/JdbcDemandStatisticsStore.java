package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.application.statistics.DemandStatisticsStore;
import com.gustler.backend.forecasting.domain.statistics.DailyStopDemand;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsBaseline;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsRun.FoldCursor;
import com.gustler.backend.forecasting.domain.statistics.RebuildScope;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsRun.ReduceCursor;
import com.gustler.backend.forecasting.domain.statistics.StopDemandCellTotals;
import com.gustler.backend.forecasting.domain.statistics.TimeSlot;
import com.gustler.backend.forecasting.domain.statistics.VehicleHourlyDemand;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDemandStatisticsStore implements DemandStatisticsStore {

    private static final List<String> STAGES =
        List.of("stop_demand_capacity_stage", "stop_demand_day_stage", "stop_demand_cell_stage");

    private final JdbcClient jdbc;

    public JdbcDemandStatisticsStore(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void limitStatementTime() {
        jdbc.sql("""
            SELECT set_config('statement_timeout','500ms',true),set_config('lock_timeout','100ms',true),
                   set_config('work_mem','1MB',true),set_config('max_parallel_workers_per_gather','0',true)
            """).query().singleRow();
    }

    @Override
    public boolean qualityRebuildPending(final long routeVersionId, final RebuildScope scope) {
        return jdbc.sql("""
            SELECT EXISTS(SELECT 1 FROM trip_quality_rebuild WHERE route_version_id = :version AND NOT completed
              AND (:vehicle = '' OR vehicle_id IN ('', :vehicle)))
            """).param("version", routeVersionId).param("vehicle", scope.vehicleId()).query(Boolean.class).single();
    }

    @Override
    public DemandStatisticsBaseline baseline(final long routeVersionId) {
        jdbc.sql("INSERT INTO stop_demand_baseline(route_version_id) VALUES (?) ON CONFLICT DO NOTHING")
            .param(routeVersionId).update();
        return jdbc.sql("SELECT initialized, data_until FROM stop_demand_baseline WHERE route_version_id = ?")
            .param(routeVersionId)
            .query((rs, row) -> new DemandStatisticsBaseline(rs.getBoolean("initialized"),
                instantOrNull(rs.getObject("data_until", OffsetDateTime.class))))
            .single();
    }

    @Override
    public void recordBaseline(final long routeVersionId, final boolean initialized, final Instant dataUntil) {
        jdbc.sql("""
            INSERT INTO stop_demand_baseline(route_version_id,initialized,data_until) VALUES (?, ?, ?)
            ON CONFLICT(route_version_id) DO UPDATE SET
              initialized=stop_demand_baseline.initialized OR EXCLUDED.initialized,
              data_until=GREATEST(stop_demand_baseline.data_until,EXCLUDED.data_until)
            """).params(routeVersionId, initialized, offsetOf(dataUntil)).update();
    }

    @Override
    public Optional<String> nextAccumulationVehicle(final long routeVersionId, final long inputUntilId,
        final String vehicleCursor, final long inputCursor) {
        return jdbc.sql("""
            SELECT vehicle_id FROM stop_demand_pending_sample
            WHERE route_version_id=:version AND id<=:upper
              AND (vehicle_id>:vehicle OR (vehicle_id=:vehicle AND id>:cursor))
            ORDER BY vehicle_id,id LIMIT 1
            """).param("version", routeVersionId).param("upper", inputUntilId).param("vehicle", vehicleCursor)
            .param("cursor", inputCursor).query(String.class).optional();
    }

    @Override
    public void addToCurrentTotals(final long routeVersionId, final List<VehicleHourlyDemand> increments) {
        for (final VehicleHourlyDemand increment : increments) {
            jdbc.sql("""
                INSERT INTO stop_demand_current_total AS total (
                    route_version_id, vehicle_id, arrived_hour_start, target_stop_order,
                    sample_count, arrival_seats_sum, net_boarding_sum
                ) VALUES (:version, :vehicle, :hour, :stop, :count, :seats, :net)
                ON CONFLICT (route_version_id, vehicle_id, arrived_hour_start, target_stop_order)
                DO UPDATE SET sample_count = total.sample_count + EXCLUDED.sample_count,
                              arrival_seats_sum = total.arrival_seats_sum + EXCLUDED.arrival_seats_sum,
                              net_boarding_sum = total.net_boarding_sum + EXCLUDED.net_boarding_sum
                """).param("version", routeVersionId).param("vehicle", increment.vehicleId())
                .param("hour", offsetOf(increment.arrivedHourStart())).param("stop", increment.targetStopOrder())
                .param("count", increment.sampleCount()).param("seats", increment.arrivalSeatsSum())
                .param("net", increment.netBoardingSum()).update();
        }
    }

    @Override
    public void registerVehicle(final long routeVersionId, final String vehicleId) {
        jdbc.sql("INSERT INTO stop_demand_vehicle(route_version_id,vehicle_id) VALUES (?, ?) ON CONFLICT DO NOTHING")
            .params(routeVersionId, vehicleId).update();
    }

    @Override
    public int clearStagePage(final long routeVersionId, final int limit) {
        // 표 이름은 코드에 고정돼 있다. 외부 입력으로 SQL 식별자를 만들지 않는다.
        for (final String table : STAGES) {
            final int removed = jdbc.sql("DELETE FROM " + table + " WHERE ctid IN (SELECT ctid FROM " + table
                    + " WHERE route_version_id=? LIMIT ?)")
                .params(routeVersionId, limit).update();
            if (removed > 0) {
                return removed;
            }
        }
        return 0;
    }

    @Override
    public void stageCapacities(final long routeVersionId, final Instant dataUntil, final long inputUntilId) {
        // 이 SQL 한 번의 snapshot에서 모든 정원을 고정한다. 다음 단계에서는 관측을 다시 읽지 않는다.
        jdbc.sql("""
            INSERT INTO stop_demand_capacity_stage(route_version_id,vehicle_id,capacity)
            SELECT :version,vehicles.vehicle_id,GREATEST(capacity.remaining_seats,1)
            FROM (
                SELECT vehicle_id FROM stop_demand_vehicle WHERE route_version_id=:version
                UNION SELECT vehicle_id FROM stop_demand_pending_sample WHERE route_version_id=:version
                  AND id<=:inputUntilId
            ) vehicles
            CROSS JOIN LATERAL (
                SELECT observation.remaining_seats
                FROM vehicle_observation observation
                JOIN observation_batch batch ON batch.id=observation.observation_batch_id
                WHERE observation.route_version_id=:version AND observation.vehicle_id=vehicles.vehicle_id
                  AND observation.remaining_seats IS NOT NULL
                  AND batch.response_received_at<=:dataUntil
                  AND EXISTS(SELECT 1 FROM forecast_eligible_observation eligible WHERE eligible.id=observation.id)
                ORDER BY observation.remaining_seats DESC LIMIT 1
            ) capacity
            """).param("version", routeVersionId).param("inputUntilId", inputUntilId)
            .param("dataUntil", offsetOf(dataUntil)).update();
    }

    @Override
    public List<FoldRow> foldPage(final long routeVersionId, final Optional<FoldCursor> after, final int limit) {
        return jdbc.sql("""
            SELECT total.*,capacity.capacity FROM stop_demand_current_total total
            LEFT JOIN stop_demand_capacity_stage capacity ON capacity.route_version_id=total.route_version_id
                AND capacity.vehicle_id=total.vehicle_id
            WHERE total.route_version_id=:version
              AND (total.vehicle_id,total.arrived_hour_start,total.target_stop_order)
                  >(:vehicleCursor,COALESCE(CAST(:hourCursor AS timestamptz),'-infinity'::timestamptz),:stopCursor)
            ORDER BY total.vehicle_id,total.arrived_hour_start,total.target_stop_order LIMIT :limit
            """).param("version", routeVersionId)
            .param("vehicleCursor", after.map(FoldCursor::vehicleId).orElse(""))
            .param("hourCursor", after.map(cursor -> offsetOf(cursor.arrivedHourStart())).orElse(null))
            .param("stopCursor", after.map(FoldCursor::targetStopOrder).orElse(0))
            .param("limit", limit)
            .query((rs, row) -> new FoldRow(new VehicleHourlyDemand(rs.getString("vehicle_id"),
                rs.getObject("arrived_hour_start", OffsetDateTime.class).toInstant(), rs.getInt("target_stop_order"),
                rs.getLong("sample_count"), rs.getLong("arrival_seats_sum"), rs.getLong("net_boarding_sum")),
                rs.getObject("capacity", Integer.class)))
            .list();
    }

    @Override
    public void addToDayStage(final long routeVersionId, final List<DailyStopDemand> days) {
        for (final DailyStopDemand day : days) {
            jdbc.sql("""
                INSERT INTO stop_demand_day_stage AS day(route_version_id,stop_order,time_slot,arrival_date,
                    fill_rate_total,net_boarding_total,capacity_total,sample_count)
                VALUES (?,?,?,?,?,?,?,?)
                ON CONFLICT(route_version_id,stop_order,time_slot,arrival_date) DO UPDATE SET
                    fill_rate_total=day.fill_rate_total+EXCLUDED.fill_rate_total,
                    net_boarding_total=day.net_boarding_total+EXCLUDED.net_boarding_total,
                    capacity_total=day.capacity_total+EXCLUDED.capacity_total,sample_count=day.sample_count+EXCLUDED.sample_count
                """).params(routeVersionId, day.stopOrder(), storedTimeSlotOf(day.timeSlot()), day.arrivalDate(),
                    day.fillRateTotal(), day.netBoardingTotal(), day.capacityTotal(), day.sampleCount()).update();
        }
    }

    @Override
    public List<DailyStopDemand> reducePage(final long routeVersionId, final Optional<ReduceCursor> after,
        final int limit) {
        return jdbc.sql("""
            SELECT * FROM stop_demand_day_stage
            WHERE route_version_id=:version
              AND (stop_order,time_slot,arrival_date)
                  >(:stopCursor,:slotCursor,COALESCE(CAST(:dayCursor AS date),'-infinity'::date))
            ORDER BY stop_order,time_slot,arrival_date LIMIT :limit
            """).param("version", routeVersionId)
            .param("stopCursor", after.map(ReduceCursor::stopOrder).orElse(0))
            .param("slotCursor", after.map(cursor -> storedTimeSlotOf(cursor.timeSlot())).orElse(""))
            .param("dayCursor", after.map(ReduceCursor::arrivalDate).orElse(null))
            .param("limit", limit)
            .query((rs, row) -> new DailyStopDemand(rs.getInt("stop_order"), timeSlotOf(rs.getString("time_slot")),
                rs.getObject("arrival_date", LocalDate.class), rs.getDouble("fill_rate_total"),
                rs.getLong("net_boarding_total"), rs.getLong("capacity_total"), rs.getLong("sample_count")))
            .list();
    }

    @Override
    public void addToCellStage(final long routeVersionId, final List<StopDemandCellTotals> cells) {
        for (final StopDemandCellTotals cell : cells) {
            jdbc.sql("""
                INSERT INTO stop_demand_cell_stage AS cell(route_version_id,stop_order,time_slot,
                    fill_rate_total,net_boarding_rate_total,sample_count,day_count) VALUES(?,?,?,?,?,?,?)
                ON CONFLICT(route_version_id,stop_order,time_slot) DO UPDATE SET
                    fill_rate_total=cell.fill_rate_total+EXCLUDED.fill_rate_total,
                    net_boarding_rate_total=cell.net_boarding_rate_total+EXCLUDED.net_boarding_rate_total,
                    sample_count=cell.sample_count+EXCLUDED.sample_count,day_count=cell.day_count+EXCLUDED.day_count
                """).params(routeVersionId, cell.stopOrder(), storedTimeSlotOf(cell.timeSlot()), cell.fillRateTotal(),
                    cell.netBoardingRateTotal(), cell.sampleCount(), cell.dayCount()).update();
        }
    }

    @Override
    public List<StopDemandCellTotals> stagedCells(final long routeVersionId) {
        return jdbc.sql("""
            SELECT stop_order,time_slot,fill_rate_total,net_boarding_rate_total,sample_count,day_count
            FROM stop_demand_cell_stage WHERE route_version_id=? ORDER BY stop_order,time_slot
            """).param(routeVersionId)
            .query((rs, row) -> new StopDemandCellTotals(rs.getInt("stop_order"), timeSlotOf(rs.getString("time_slot")),
                rs.getDouble("fill_rate_total"), rs.getDouble("net_boarding_rate_total"), rs.getLong("sample_count"),
                rs.getInt("day_count")))
            .list();
    }

    private static String storedTimeSlotOf(final TimeSlot timeSlot) {
        return timeSlot.name().toLowerCase(Locale.ROOT);
    }

    private static TimeSlot timeSlotOf(final String stored) {
        return TimeSlot.valueOf(stored.toUpperCase(Locale.ROOT));
    }

    private static Instant instantOrNull(final OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime offsetOf(final Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
