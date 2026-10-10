package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.application.statistics.StatisticsArchiveReader.Reference;
import com.gustler.backend.forecasting.application.statistics.ArchivedDemandStatisticsStore;
import com.gustler.backend.forecasting.application.statistics.StatisticsArchiveReader.Key;
import com.gustler.backend.forecasting.application.statistics.StatisticsArchiveReader.Row;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsRebuild;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcArchivedDemandStatisticsStore implements ArchivedDemandStatisticsStore {
    private final JdbcClient jdbc;

    public JdbcArchivedDemandStatisticsStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public boolean hasArchive(long routeVersionId) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM evaluation_archive_batch WHERE route_version_id=?)")
            .param(routeVersionId).query(Boolean.class).single();
    }

    @Override
    public List<Missing> missing(long routeVersionId, List<Long> observations) {
        if (observations.isEmpty()) return List.of();
        List<Missing> rows = jdbc.sql("""
            SELECT m.vehicle_observation_id,m.target_stop_order,m.original_sha256,b.*
            FROM evaluation_archive_member m JOIN evaluation_archive_batch b ON b.id=m.batch_id
            JOIN seat_forecast f ON f.vehicle_observation_id=m.vehicle_observation_id
                AND f.target_stop_order=m.target_stop_order AND f.stops_to_target=1
            WHERE m.route_version_id=:route AND m.vehicle_observation_id IN (:ids)
              AND NOT EXISTS(SELECT 1 FROM forecast_evaluation_result e
                  WHERE e.vehicle_observation_id=m.vehicle_observation_id AND e.target_stop_order=m.target_stop_order)
            ORDER BY m.vehicle_observation_id,m.target_stop_order LIMIT 129
            """).param("route", routeVersionId).param("ids", observations).query((rs, n) -> {
                if (!"VERIFIED".equals(rs.getString("state"))) {
                    throw new IllegalStateException("원본이 없는 정산의 S3 검증 기록이 없다");
                }
                return new Missing(new Reference(rs.getObject("id", UUID.class), rs.getLong("route_version_id"),
                    rs.getLong("quality_revision"), rs.getInt("row_count"), rs.getString("manifest_sha256")),
                    new Key(rs.getLong("vehicle_observation_id"), rs.getInt("target_stop_order")), rs.getString("original_sha256"));
            }).list();
        if (rows.size() > DemandStatisticsRebuild.PAGE_SIZE) {
            throw new IllegalStateException("한 단계의 이관 입력 한도를 넘었다");
        }
        return rows;
    }

    @Override
    public List<Sample> samples(DemandStatisticsRebuild rebuild, List<Long> observations, List<Row> archived) {
        if (observations.isEmpty()) return List.of();
        String json = archived.stream().map(Row::originalJson).collect(Collectors.joining(",", "[", "]"));
        List<Sample> samples = jdbc.sql("""
            WITH archived AS MATERIALIZED (
                SELECT * FROM jsonb_populate_recordset(NULL::forecast_evaluation_result,CAST(:archive AS jsonb))
            ), evaluations AS MATERIALIZED (
                SELECT e.* FROM forecast_evaluation_result e WHERE e.vehicle_observation_id IN (:ids)
                UNION ALL
                SELECT a.* FROM archived a WHERE a.vehicle_observation_id IN (:ids)
                  AND NOT EXISTS(SELECT 1 FROM forecast_evaluation_result e
                      WHERE e.vehicle_observation_id=a.vehicle_observation_id AND e.target_stop_order=a.target_stop_order)
            )
            SELECT source.vehicle_id,e.arrived_at,f.target_stop_order,e.seats_on_arrival,
                source.remaining_seats-e.seats_on_arrival AS net_boarding
            FROM evaluations e
            JOIN forecast_eligible_observation source ON source.id=e.vehicle_observation_id
            JOIN seat_forecast f ON f.vehicle_observation_id=e.vehicle_observation_id AND f.target_stop_order=e.target_stop_order
            JOIN forecast_eligible_observation arrival ON arrival.id=e.arrival_observation_id
              AND %s
            JOIN route_stop stop ON stop.route_version_id=f.route_version_id AND stop.stop_order=f.target_stop_order
            WHERE source.route_version_id=:route AND e.route_version_id=:route
              AND (:vehicle='' OR source.vehicle_id=:vehicle)
              AND source.vehicle_id IS NOT NULL AND source.remaining_seats IS NOT NULL
              AND e.scoring_state='SETTLED' AND f.stops_to_target=1 AND stop.boarding_allowed AND e.scored_at<=:until
              AND NOT EXISTS(SELECT 1 FROM stop_demand_pending_sample pending
                  WHERE pending.prediction_observation_id=source.id AND pending.target_stop_order=f.target_stop_order
                    AND pending.id>:inputUntil)
            ORDER BY source.id,f.target_stop_order LIMIT 129
            """.formatted(EligibleObservationSql.ARRIVAL_MATCHES_SOURCE))
            .param("archive", json).param("ids", observations).param("route", rebuild.routeVersionId())
            .param("vehicle", rebuild.scope().vehicleId()).param("until", rebuild.dataUntil().atOffset(ZoneOffset.UTC))
            .param("inputUntil", rebuild.inputUntilId()).query((rs, n) -> new Sample(
                new Cell(rs.getString("vehicle_id"), rs.getObject("arrived_at", OffsetDateTime.class).toInstant().truncatedTo(ChronoUnit.HOURS),
                    rs.getInt("target_stop_order")), rs.getLong("seats_on_arrival"), rs.getLong("net_boarding"))).list();
        if (samples.size() > DemandStatisticsRebuild.PAGE_SIZE) {
            throw new IllegalStateException("한 단계의 집계 표본 한도를 넘었다");
        }
        return samples;
    }

    @Override
    public void add(DemandStatisticsRebuild rebuild, List<Total> totals) {
        for (Total total : totals) {
            jdbc.sql("""
                INSERT INTO stop_demand_rebuild_total AS target(route_version_id,scope_vehicle_id,request_id,
                    vehicle_id,arrived_hour_start,target_stop_order,sample_count,arrival_seats_sum,net_boarding_sum)
                VALUES(:route,:scope,:request,:vehicle,:hour,:stop,:count,:arrival,:net)
                ON CONFLICT(request_id,vehicle_id,arrived_hour_start,target_stop_order) DO UPDATE SET
                    sample_count=target.sample_count+EXCLUDED.sample_count,
                    arrival_seats_sum=target.arrival_seats_sum+EXCLUDED.arrival_seats_sum,
                    net_boarding_sum=target.net_boarding_sum+EXCLUDED.net_boarding_sum
                """).param("route", rebuild.routeVersionId()).param("scope", rebuild.scope().vehicleId())
                .param("request", rebuild.requestId()).param("vehicle", total.cell().vehicleId())
                .param("hour", total.cell().hour().atOffset(ZoneOffset.UTC)).param("stop", total.cell().stopOrder())
                .param("count", total.count()).param("arrival", total.arrivalSeats()).param("net", total.netBoarding()).update();
        }
    }
}
