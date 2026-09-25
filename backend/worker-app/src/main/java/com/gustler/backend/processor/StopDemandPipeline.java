package com.gustler.backend.processor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 한 호출은 한 페이지/단계만 커밋한다. 진행 중인 원합은 예보 조회에 노출하지 않는다. */
@Component
public class StopDemandPipeline {
    private final JdbcClient jdbc;
    private final TripQualityRepository quality;
    private final StopDemandRebuildWriter rebuild;
    private final StopDemandAccumulationWriter accumulation;
    private final Clock clock;
    private final Duration interval;

    public StopDemandPipeline(JdbcClient jdbc, TripQualityRepository quality, StopDemandRebuildWriter rebuild,
        StopDemandAccumulationWriter accumulation, Clock clock,
        @Value("${forecast.statistics-interval:6h}") String interval) {
        this.jdbc=jdbc; this.quality=quality; this.rebuild=rebuild; this.accumulation=accumulation; this.clock=clock;
        this.interval=DurationStyle.detectAndParse(interval);
        if (this.interval.isNegative() || this.interval.isZero()) { throw new IllegalArgumentException("통계 주기는 양수여야 한다"); }
    }

    @Transactional(timeout = 2)
    public Step step(long version) {
        StopDemandRebuildWriter.limits(jdbc);
        quality.lockRoute(version);
        jdbc.sql("INSERT INTO stop_demand_baseline(route_version_id) VALUES (?) ON CONFLICT DO NOTHING").param(version).update();
        boolean initialized=jdbc.sql("SELECT initialized FROM stop_demand_baseline WHERE route_version_id=?")
            .param(version).query(Boolean.class).single();
        boolean pendingGlobal=jdbc.sql("SELECT EXISTS(SELECT 1 FROM stop_demand_rebuild_request WHERE route_version_id=? AND vehicle_id='')")
            .param(version).query(Boolean.class).single();
        if (!initialized && !pendingGlobal) { quality.requestStatisticsRebuild(version, ""); }
        var requests=jdbc.sql("SELECT vehicle_id FROM stop_demand_rebuild_request WHERE route_version_id=? ORDER BY vehicle_id LIMIT 1")
            .param(version).query(String.class).list();
        if (!requests.isEmpty()) {
            jdbc.sql("UPDATE stop_demand_run SET phase='STALE' WHERE route_version_id=?").param(version).update();
            boolean advanced=rebuild.step(version,requests.getFirst());
            return new Step(advanced ? "PROGRESSED" : "WAITING","REBUILD",null);
        }
        long revision=jdbc.sql("SELECT quality_revision FROM route WHERE id=(SELECT route_id FROM route_version WHERE id=?)")
            .param(version).query(Long.class).single();
        var runs=jdbc.sql("SELECT * FROM stop_demand_run WHERE route_version_id=?").param(version)
            .query((rs,n)->new Run(rs.getLong("quality_revision"),rs.getString("phase"),
                rs.getObject("data_until",OffsetDateTime.class).toInstant(),rs.getLong("input_until_id"),
                rs.getString("vehicle_cursor"),rs.getLong("cursor_id"),
                rs.getObject("completed_at",OffsetDateTime.class))).list();
        Instant now=clock.instant();
        if (!runs.isEmpty()) {
            Run run=runs.getFirst();
            if (run.phase().equals("DONE") && run.revision()==revision && run.completed()!=null
                && now.isBefore(run.completed().toInstant().plus(interval))) {
                return new Step("IDLE","DONE",run.until());
            }
        }
        if (runs.isEmpty() || runs.getFirst().phase().equals("DONE") || runs.getFirst().phase().equals("STALE")
            || runs.getFirst().revision()!=revision) {
            jdbc.sql("""
                INSERT INTO stop_demand_run(route_version_id,run_id,quality_revision,phase,data_until)
                VALUES (?, ?, ?, 'CLEAN', ?)
                ON CONFLICT(route_version_id) DO UPDATE SET run_id=EXCLUDED.run_id,
                    quality_revision=EXCLUDED.quality_revision,phase='CLEAN',
                    data_until=GREATEST(stop_demand_run.data_until,EXCLUDED.data_until),
                    input_until_id=0,vehicle_cursor='',cursor_id=0,hour_cursor='-infinity',stop_cursor=0,
                    slot_cursor='',day_cursor='-infinity'
                """).params(version,UUID.randomUUID(),revision,offset(now)).update();
            return new Step("PROGRESSED","CLEAN",now);
        }
        Run run=runs.getFirst();
        switch(run.phase()) {
            case "CLEAN" -> clean(version);
            case "CAPTURE" -> { return capture(version,run); }
            case "ACCUMULATE" -> accumulate(version,run);
            case "FOLD" -> fold(version);
            case "REDUCE" -> reduce(version);
            case "PUBLISH" -> {
                publish(version,run);
                return new Step("COMPLETED","DONE",run.until());
            }
            default -> throw new IllegalStateException("알 수 없는 통계 단계: "+run.phase());
        }
        return new Step("PROGRESSED",run.phase(),run.until());
    }

    private void clean(long version) {
        // 표 이름은 코드에 고정돼 있다. 외부 입력으로 SQL 식별자를 만들지 않는다.
        for(String table:List.of("stop_demand_capacity_stage","stop_demand_day_stage","stop_demand_cell_stage")) {
            int removed=jdbc.sql("DELETE FROM "+table+" WHERE ctid IN (SELECT ctid FROM "+table+" WHERE route_version_id=? LIMIT 128)")
                .param(version).update();
            if(removed>0) { return; }
        }
        phase(version,"CAPTURE");
    }

    private Step capture(long version,Run run) {
        Instant until=clock.instant().isAfter(run.until()) ? clock.instant() : run.until();
        jdbc.sql("""
            UPDATE stop_demand_run SET data_until=GREATEST(?,(SELECT data_until FROM stop_demand_baseline WHERE route_version_id=?)),
                input_until_id=(SELECT COALESCE(max(id),0) FROM stop_demand_pending_sample)
            WHERE route_version_id=?
            """).params(offset(until),version,version).update();
        // 이 SQL 한 번의 snapshot에서 모든 정원을 고정한다. 다음 단계에서는 관측을 다시 읽지 않는다.
        jdbc.sql("""
            INSERT INTO stop_demand_capacity_stage(route_version_id,vehicle_id,capacity)
            SELECT :version,vehicles.vehicle_id,GREATEST(capacity.remaining_seats,1)
            FROM (
                SELECT vehicle_id FROM stop_demand_vehicle WHERE route_version_id=:version
                UNION SELECT vehicle_id FROM stop_demand_pending_sample WHERE route_version_id=:version
                  AND id<=(SELECT input_until_id FROM stop_demand_run WHERE route_version_id=:version)
            ) vehicles
            CROSS JOIN LATERAL (
                SELECT observation.remaining_seats
                FROM vehicle_observation observation
                JOIN observation_batch batch ON batch.id=observation.observation_batch_id
                WHERE observation.route_version_id=:version AND observation.vehicle_id=vehicles.vehicle_id
                  AND observation.remaining_seats IS NOT NULL
                  AND batch.response_received_at<=(SELECT data_until FROM stop_demand_run WHERE route_version_id=:version)
                  AND EXISTS(SELECT 1 FROM forecast_eligible_observation eligible WHERE eligible.id=observation.id)
                ORDER BY observation.remaining_seats DESC LIMIT 1
            ) capacity
            """).param("version",version).update();
        phase(version,"ACCUMULATE");
        Instant fixed=jdbc.sql("SELECT data_until FROM stop_demand_run WHERE route_version_id=?").param(version)
            .query(OffsetDateTime.class).single().toInstant();
        return new Step("STARTED","ACCUMULATE",fixed);
    }

    private void accumulate(long version,Run run) {
        var vehicles=jdbc.sql("""
            SELECT vehicle_id FROM stop_demand_pending_sample
            WHERE route_version_id=:version AND id<=:upper
              AND (vehicle_id>:vehicle OR (vehicle_id=:vehicle AND id>:cursor))
            ORDER BY vehicle_id,id LIMIT 1
            """).param("version",version).param("upper",run.inputUntil()).param("vehicle",run.vehicle())
            .param("cursor",run.cursor()).query(String.class).list();
        if(vehicles.isEmpty()) {
            jdbc.sql("UPDATE stop_demand_run SET phase='FOLD',vehicle_cursor='',cursor_id=0 WHERE route_version_id=?")
                .param(version).update();
            return;
        }
        String vehicle=vehicles.getFirst();
        var result=accumulation.apply(version,vehicle,vehicle.equals(run.vehicle()) ? run.cursor() : 0,run.inputUntil(),run.until());
        if(result.waitingForRebuild()) { return; }
        jdbc.sql("UPDATE stop_demand_run SET vehicle_cursor=?,cursor_id=? WHERE route_version_id=?")
            .params(vehicle,result.nextInputId(),version).update();
    }

    private void fold(long version) {
        var rows=jdbc.sql("""
            SELECT total.*,capacity.capacity FROM stop_demand_current_total total
            JOIN stop_demand_run run ON run.route_version_id=total.route_version_id
            LEFT JOIN stop_demand_capacity_stage capacity ON capacity.route_version_id=total.route_version_id
                AND capacity.vehicle_id=total.vehicle_id
            WHERE total.route_version_id=?
              AND (total.vehicle_id,total.arrived_hour_start,total.target_stop_order)>(run.vehicle_cursor,run.hour_cursor,run.stop_cursor)
            ORDER BY total.vehicle_id,total.arrived_hour_start,total.target_stop_order LIMIT 128
            """).param(version).query((rs,n)->new Bucket(rs.getString("vehicle_id"),
                rs.getObject("arrived_hour_start",OffsetDateTime.class),rs.getInt("target_stop_order"),
                rs.getLong("sample_count"),rs.getLong("arrival_seats_sum"),rs.getLong("net_boarding_sum"),
                rs.getObject("capacity",Integer.class))).list();
        for(Bucket row:rows) {
            if(row.capacity()==null) { continue; }
            String slot=TimeSlot.of(row.hour().toInstant(),clock).name().toLowerCase(Locale.ROOT);
            LocalDate day=row.hour().atZoneSameInstant(clock.getZone()).toLocalDate();
            jdbc.sql("""
                INSERT INTO stop_demand_day_stage AS day(route_version_id,stop_order,time_slot,arrival_date,
                    fill_rate_total,net_boarding_total,capacity_total,sample_count)
                VALUES (?,?,?,?,?,?,?,?)
                ON CONFLICT(route_version_id,stop_order,time_slot,arrival_date) DO UPDATE SET
                    fill_rate_total=day.fill_rate_total+EXCLUDED.fill_rate_total,
                    net_boarding_total=day.net_boarding_total+EXCLUDED.net_boarding_total,
                    capacity_total=day.capacity_total+EXCLUDED.capacity_total,sample_count=day.sample_count+EXCLUDED.sample_count
                """).params(version,row.stop(),slot,day,row.count()-row.seats()/(double)row.capacity(),
                    row.net(),Math.multiplyExact(row.count(),row.capacity()),row.count()).update();
        }
        if(rows.size()<128) { phase(version,"REDUCE"); }
        else {
            Bucket last=rows.getLast();
            jdbc.sql("UPDATE stop_demand_run SET vehicle_cursor=?,hour_cursor=?,stop_cursor=? WHERE route_version_id=?")
                .params(last.vehicle(),last.hour(),last.stop(),version).update();
        }
        if(rows.size()<128) {
            jdbc.sql("UPDATE stop_demand_run SET stop_cursor=0 WHERE route_version_id=?").param(version).update();
        }
    }

    private void reduce(long version) {
        var rows=jdbc.sql("""
            SELECT day.* FROM stop_demand_day_stage day JOIN stop_demand_run run USING(route_version_id)
            WHERE route_version_id=? AND (day.stop_order,day.time_slot,day.arrival_date)>(run.stop_cursor,run.slot_cursor,run.day_cursor)
            ORDER BY day.stop_order,day.time_slot,day.arrival_date LIMIT 128
            """).param(version).query((rs,n)->new Day(rs.getInt("stop_order"),rs.getString("time_slot"),
                rs.getObject("arrival_date",LocalDate.class),rs.getDouble("fill_rate_total")/rs.getLong("sample_count"),
                rs.getLong("net_boarding_total")/(double)rs.getLong("capacity_total"),rs.getLong("sample_count"))).list();
        for(Day day:rows) {
            jdbc.sql("""
                INSERT INTO stop_demand_cell_stage AS cell(route_version_id,stop_order,time_slot,
                    fill_rate_total,net_boarding_rate_total,sample_count,day_count) VALUES(?,?,?,?,?,?,1)
                ON CONFLICT(route_version_id,stop_order,time_slot) DO UPDATE SET
                    fill_rate_total=cell.fill_rate_total+EXCLUDED.fill_rate_total,
                    net_boarding_rate_total=cell.net_boarding_rate_total+EXCLUDED.net_boarding_rate_total,
                    sample_count=cell.sample_count+EXCLUDED.sample_count,day_count=cell.day_count+1
                """).params(version,day.stop(),day.slot(),day.fill(),day.net(),day.count()).update();
        }
        if(rows.size()<128) { phase(version,"PUBLISH"); }
        else {
            Day last=rows.getLast();
            jdbc.sql("UPDATE stop_demand_run SET stop_cursor=?,slot_cursor=?,day_cursor=? WHERE route_version_id=?")
                .params(last.stop(),last.slot(),last.date(),version).update();
        }
    }

    private void publish(long version,Run run) {
        String calculation=StopDemandStatisticsJob.CURRENT_CALCULATION_VERSION;
        int revision=jdbc.sql("""
            SELECT COALESCE(max(revision),0)+1 FROM (
                SELECT max(revision) AS revision FROM stop_demand_statistics WHERE route_version_id=? AND calculation_version=?
                UNION ALL SELECT max(revision) FROM stop_demand_publication WHERE route_version_id=? AND calculation_version=?
            ) revisions
            """).params(version,calculation,version,calculation).query(Integer.class).single();
        OffsetDateTime computedAt=offset(clock.instant());
        jdbc.sql("""
            INSERT INTO stop_demand_statistics(route_version_id,stop_order,time_slot,calculation_version,revision,
                average_fill_rate,average_net_boarding_rate,sample_count,day_count,data_until,computed_at,quality_revision)
            SELECT route_version_id,stop_order,time_slot,?,?,fill_rate_total/day_count,net_boarding_rate_total/day_count,
                sample_count,day_count,?,?,? FROM stop_demand_cell_stage WHERE route_version_id=?
            """).params(calculation,revision,offset(run.until()),computedAt,run.revision(),version).update();
        jdbc.sql("""
            INSERT INTO stop_demand_publication(route_version_id,calculation_version,revision,data_until,computed_at,quality_revision)
            VALUES(?,?,?,?,?,?)
            """).params(version,calculation,revision,offset(run.until()),computedAt,run.revision()).update();
        jdbc.sql("UPDATE stop_demand_run SET phase='DONE',completed_at=? WHERE route_version_id=?")
            .params(computedAt,version).update();
    }

    private void phase(long version,String phase) {
        jdbc.sql("UPDATE stop_demand_run SET phase=? WHERE route_version_id=?").params(phase,version).update();
    }
    private static OffsetDateTime offset(Instant instant) { return instant.atOffset(ZoneOffset.UTC); }
    public record Step(String status,String phase,Instant dataUntil) { }
    private record Run(long revision,String phase,Instant until,long inputUntil,String vehicle,long cursor,OffsetDateTime completed) { }
    private record Bucket(String vehicle,OffsetDateTime hour,int stop,long count,long seats,long net,Integer capacity) { }
    private record Day(int stop,String slot,LocalDate date,double fill,double net,long count) { }
}
