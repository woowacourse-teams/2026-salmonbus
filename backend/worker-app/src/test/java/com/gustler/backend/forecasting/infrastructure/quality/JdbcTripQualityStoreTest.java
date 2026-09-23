package com.gustler.backend.forecasting.infrastructure.quality;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.forecasting.application.quality.TripQualityInvestigationService;
import com.gustler.backend.forecasting.domain.quality.QualityObservationBatch;
import com.gustler.backend.support.IntegrationTest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class JdbcTripQualityStoreTest {
    private static final Instant START = Instant.parse("2026-09-21T00:00:00Z");
    private static final Instant OLD_STARTED_AT = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant OLD_INVESTIGATED_AT = Instant.parse("2026-09-02T00:00:00Z");
    private static final String VEHICLE = "bus-1";

    @Autowired
    private JdbcClient jdbc;

    @ParameterizedTest
    @ValueSource(strings = {"SEARCH_START", "REPLAY", "DONE"})
    void 조사_중인_차량의_새_이상_관측은_기존_진행_정보와_품질_버전을_바꾸지_않는다(String phase) {
        // given
        final var fixture = existingInvestigation(phase, false);
        final var newest = observation(fixture.version(), 4, VEHICLE, 82);
        changeGap(fixture.version(), 120);
        final var before = investigation(fixture.version());
        final var rawBefore = rawObservations(fixture.version());
        final List<Long> retained = new ArrayList<>();
        final var service = service(retained);

        // when
        service.observationsStored(event(fixture.version(), newest));
        service.observationsStored(event(fixture.version(), newest));

        // then
        assertThat(investigation(fixture.version())).isEqualTo(before);
        assertThat(qualityRevision(fixture.route())).isEqualTo(11);
        assertThat(retained).isEmpty();
        assertThat(rawObservations(fixture.version())).isEqualTo(rawBefore);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DONE", "REPLAY", "SEARCH_START"})
    void 완료한_조사는_새_근거와_현재_정책으로_다시_시작하고_같은_요청은_한번만_반영한다(String phase) {
        // given
        final var fixture = existingInvestigation(phase, true);
        final var newest = observation(fixture.version(), 4, VEHICLE, 82);
        changeGap(fixture.version(), 120);
        final var rawBefore = rawObservations(fixture.version());
        final Instant changedAt = jdbc.sql("SELECT CURRENT_TIMESTAMP")
            .query(OffsetDateTime.class).single().toInstant();
        final List<Long> retained = new ArrayList<>();
        final var service = service(retained);
        final var event = event(fixture.version(), newest);

        // when
        service.observationsStored(event);
        final var restarted = investigation(fixture.version());
        service.observationsStored(event);

        // then
        assertThat(restarted).isEqualTo(new InvestigationRow(
            newest.batchId(), newest.observedAt(), newest.observedAt(), 120, false, "SEARCH_START",
            newest.observationId(), newest.observationId(), null, null, false, false,
            changedAt, changedAt));
        assertThat(investigation(fixture.version())).isEqualTo(restarted);
        assertThat(qualityRevision(fixture.route())).isEqualTo(12);
        assertThat(retained).containsExactly(newest.observationId());
        assertThat(rawObservations(fixture.version())).isEqualTo(rawBefore);
        assertThat(jdbc.sql("""
            SELECT count(*) FROM observation_trip_assignment assignment
            JOIN vehicle_observation observation ON observation.id=assignment.observation_id
            WHERE observation.route_version_id=?
            """).param(fixture.version()).query(Integer.class).single()).isZero();
    }

    private TripQualityInvestigationService service(List<Long> retained) {
        return new TripQualityInvestigationService(new JdbcTripQualityStore(jdbc),
            new JdbcRouteDataQualityAccess(jdbc), retained::addAll);
    }

    private Fixture existingInvestigation(String phase, final boolean completed) {
        final long route = jdbc.sql("""
            INSERT INTO route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name)
            VALUES ('quality-store-test','TEST','quality-store-test','test','start','end') RETURNING id
            """).query(Long.class).single();
        final long version = jdbc.sql("""
            INSERT INTO route_version(route_id,content_digest,valid_from,turn_sequence)
            VALUES (?,?,'2026-09-20T00:00:00Z',3) RETURNING id
            """).param(route).param("0".repeat(64)).query(Long.class).single();
        jdbc.sql("""
            INSERT INTO route_stop(route_version_id,stop_order,stop_id,name,direction,boarding_allowed)
            SELECT ?,n,'s'||n,'stop'||n,CASE WHEN n<=3 THEN 'UP' ELSE 'DOWN' END,true
            FROM generate_series(1,4) n
            """).param(version).update();
        jdbc.sql("INSERT INTO route_data_quality(route_id,quality_revision) VALUES (?,11)")
            .param(route).update();
        jdbc.sql("""
            INSERT INTO route_version_quality_policy(route_version_id,maximum_observation_gap_seconds,observation_gap_evidence)
            VALUES (?,60,'synthetic test')
            """).param(version).update();
        final var start = observation(version, 1, VEHICLE, 44);
        final var evidence = observation(version, 2, VEHICLE, 71);
        final var cursor = observation(version, 3, VEHICLE, 44);
        // 완료 기록에 남은 진행 정보도 재시작할 때 모두 교체해야 한다.
        jdbc.sql("""
            INSERT INTO trip_quality_rebuild(route_version_id,vehicle_id,last_batch_at,last_batch_id,
                until_at,maximum_gap_seconds,completed,phase,evidence_observation_id,anchor_observation_id,
                previous_observation_id,boundary_candidate_observation_id,include_cursor,can_release,
                started_at,investigated_at)
            VALUES (?,?,?,?,?,60,?,?,?,?,?,?,true,true,?,?)
            """).param(version).param(VEHICLE).param(offset(cursor.observedAt())).param(cursor.batchId())
            .param(offset(evidence.observedAt())).param(completed).param(phase)
            .param(evidence.observationId()).param(start.observationId()).param(cursor.observationId())
            .param(start.observationId()).param(offset(OLD_STARTED_AT)).param(offset(OLD_INVESTIGATED_AT)).update();
        return new Fixture(route, version);
    }

    private StoredObservation observation(final long version, final int step, String vehicle, final int seats) {
        final Instant at = START.plusSeconds(step * 10L);
        final long batch = jdbc.sql("""
            INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,
                requested_at,response_received_at,outcome,normalization_version,collection_strategy_version)
            VALUES (?,?,1,?,?,?,'SUCCESS_ROWS','test','test') RETURNING id
            """).param(version).param(offset(at)).param("quality-" + step)
            .param(offset(at)).param(offset(at)).query(Long.class).single();
        final long observation = jdbc.sql("""
            INSERT INTO vehicle_observation(observation_batch_id,route_version_id,source_row_number,vehicle_id,
                vehicle_trip_key,stop_order,stop_id,running_state,passed_stop_order,remaining_seats)
            VALUES (?,?,1,?,?,2,'s2',2,2,?) RETURNING id
            """).param(batch).param(version).param(vehicle).param("raw-trip-" + step).param(seats)
            .query(Long.class).single();
        return new StoredObservation(batch, observation, at, vehicle, seats);
    }

    private void changeGap(final long version, final int seconds) {
        jdbc.sql("""
            UPDATE route_version_quality_policy SET maximum_observation_gap_seconds=?,observation_gap_evidence='changed test policy'
            WHERE route_version_id=?
            """).param(seconds).param(version).update();
    }

    private InvestigationRow investigation(final long version) {
        return jdbc.sql("""
            SELECT last_batch_id,last_batch_at,until_at,maximum_gap_seconds,completed,phase,
                evidence_observation_id,anchor_observation_id,previous_observation_id,boundary_candidate_observation_id,
                include_cursor,can_release,started_at,investigated_at
            FROM trip_quality_rebuild WHERE route_version_id=? AND vehicle_id=?
            """).param(version).param(VEHICLE).query((row, index) -> new InvestigationRow(
                row.getLong("last_batch_id"), row.getObject("last_batch_at", OffsetDateTime.class).toInstant(),
                row.getObject("until_at", OffsetDateTime.class).toInstant(), row.getInt("maximum_gap_seconds"),
                row.getBoolean("completed"), row.getString("phase"), row.getLong("evidence_observation_id"),
                row.getLong("anchor_observation_id"), row.getObject("previous_observation_id", Long.class),
                row.getObject("boundary_candidate_observation_id", Long.class), row.getBoolean("include_cursor"),
                row.getBoolean("can_release"), row.getObject("started_at", OffsetDateTime.class).toInstant(),
                row.getObject("investigated_at", OffsetDateTime.class).toInstant())).single();
    }

    private long qualityRevision(final long route) {
        return jdbc.sql("SELECT quality_revision FROM route_data_quality WHERE route_id=?")
            .param(route).query(Long.class).single();
    }

    private List<Map<String, Object>> rawObservations(final long version) {
        return jdbc.sql("SELECT * FROM vehicle_observation WHERE route_version_id=? ORDER BY id")
            .param(version).query().listOfRows();
    }

    private static QualityObservationBatch event(final long version, StoredObservation observation) {
        return new QualityObservationBatch(observation.batchId(), version, observation.observedAt(),
            List.of(new QualityObservationBatch.Row(observation.observationId(), observation.vehicleId(), observation.seats())));
    }

    private static OffsetDateTime offset(Instant at) { return at.atOffset(ZoneOffset.UTC); }

    private record Fixture(long route, long version) { }

    private record StoredObservation(long batchId, long observationId, Instant observedAt, String vehicleId, int seats) { }

    private record InvestigationRow(long cursorBatchId, Instant cursorAt, Instant evidenceAt, int maximumGapSeconds,
        boolean completed, String phase, long evidenceObservationId, long anchorObservationId,
        Long previousObservationId, Long boundaryCandidateObservationId, boolean includeCursor, boolean canRelease,
        Instant startedAt, Instant investigatedAt) { }
}
