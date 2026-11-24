package com.gustler.backend.forecasting.support;

import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareObservation;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 재생 자료의 노선 하나를 로컬 DB에 넣는다.
 *
 * <p>수집이 막 저장한 것과 같은 모양으로 넣는다. 배치의 {@code input_confirmed_at}은 비워 두고, 착수 시각은 예보가 배치를 처리할
 * 때 예보 실행에 적는다. 그날 앞의 관측은 관측 테이블에 넣지 않는다. 그래서 예보는 그날의 첫 배치를 앞선 관측이 없는 상태에서
 * 시작한다.
 *
 * <p>차량 정원 이력은 수집이 만든 것이 아니라 이 자료의 관측으로 흉내 낸 것이다. 차량마다 (착수 시각, 배치 번호) 순서로 본
 * 좌석(1~70)의 누적 최댓값을 그 관측의 착수 시각에 알려진 정원으로 적는다. 그날 앞의 차량별 마지막 관측과 정원을 올린 관측도
 * 근거에 넣는다. 수집이 걸러 낸 좌석은 알 수 없어 반영하지 못한다.
 */
final class WorkerReplayLoader {

    private static final String SOURCE_ID = "GBIS";
    private static final int ROWS_PER_STATEMENT = 500;
    private static final Comparator<AlightingAwareObservation> EVIDENCE_ORDER = Comparator
        .comparing(AlightingAwareObservation::vehicleId)
        .thenComparing(AlightingAwareObservation::receivedAt)
        .thenComparingLong(AlightingAwareObservation::batchId);

    private final JdbcClient jdbc;

    WorkerReplayLoader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 노선·판본·정류장·배치·관측·정원 이력을 넣는다.
     *
     * @return 넣은 노선의 번호
     */
    long load(WorkerReplayFixture fixture) {
        final long routeId = jdbc.sql("""
            INSERT INTO route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name)
            VALUES(?,?,?,?,'A','B') RETURNING id
            """).params(
                    fixture.sourceRouteId(),
                    SOURCE_ID,
                    fixture.sourceRouteId(),
                    fixture.route()
            )
            .query(Long.class).single();
        insertVersions(fixture, routeId);
        insertBatches(fixture);
        insertObservations(fixture);
        insertCapacityHistory(fixture);
        return routeId;
    }

    private void insertVersions(WorkerReplayFixture fixture, final long routeId) {
        List<WorkerReplayFixture.Stop> stops = fixture.stops();
        for (WorkerReplayFixture.Version version : fixture.versions()) {
            jdbc.sql("""
                INSERT INTO route_version(id,route_id,turn_sequence,content_digest,valid_from,valid_to)
                OVERRIDING SYSTEM VALUE VALUES(?,?,?,?,?,?)
                """).params(Arrays.asList(
                        version.id(),
                        routeId,
                        version.turnSequence(),
                        version.contentDigest(),
                        version.validFrom(),
                        version.validTo()
                )).update();
            insert("""
                INSERT INTO route_stop(route_version_id,stop_order,stop_id,name,direction,boarding_allowed) VALUES
                """, "(?,?,?,?,?,?)", "", stops.stream()
                .map(stop -> Arrays.<Object>asList(
                        version.id(),
                        stop.order(),
                        stop.id(),
                        stop.name(),
                        stop.direction(),
                        stop.boardingAllowed()
                ))
                .toList());
        }
    }

    private void insertBatches(WorkerReplayFixture fixture) {
        insert("""
            INSERT INTO observation_batch(id,route_version_id,scheduled_at,attempt_number,attempt_key,requested_at,
              response_received_at,outcome,stored_rows,normalization_version,collection_strategy_version,
              ingestion_origin)
            OVERRIDING SYSTEM VALUE VALUES
            """, "(?,?,?,1,?,?,?,'SUCCESS_ROWS',?,'normalization-v1.0.0','adaptive-kst-v1.0.1','LIVE')", "",
            fixture.batches().stream()
                .map(batch -> Arrays.<Object>asList(
                        batch.id(),
                        batch.routeVersionId(),
                        time(batch.responseAt()),
                        "replay-" + batch.id(),
                        time(batch.responseAt()),
                        time(batch.responseAt()),
                        batch.storedRows()
                ))
                .toList());
    }

    private void insertObservations(WorkerReplayFixture fixture) {
        Map<Integer, String> stopIds = new HashMap<>();
        fixture.stops().forEach(stop -> stopIds.put(stop.order(), stop.id()));
        Map<Long, Integer> rowNumbers = new HashMap<>();
        List<List<Object>> rows = new ArrayList<>();
        for (AlightingAwareObservation observation : fixture.observations()) {
            String stopId = stopIds.get(observation.stopOrder());
            if (stopId == null) {
                throw new IllegalArgumentException("재생 자료의 관측이 정류장 목록에 없는 순번을 가리킨다: " + observation.observationId());
            }
            final int rowNumber = rowNumbers.merge(observation.batchId(), 1, Integer::sum) - 1;
            rows.add(Arrays.asList(
                    observation.observationId(),
                    observation.batchId(),
                    observation.routeVersionId(),
                    rowNumber,
                    observation.vehicleId(),
                    observation.stopOrder(),
                    stopId,
                    observation.state(),
                    observation.seats(),
                    seatUnknownReason(observation),
                    observation.passedStopOrder(),
                    knownCrowd(observation)
            ));
        }
        insert("""
            INSERT INTO vehicle_observation(id,observation_batch_id,route_version_id,source_row_number,vehicle_id,
              stop_order,stop_id,running_state,remaining_seats,seat_unknown_reason,passed_stop_order,crowd_level)
            OVERRIDING SYSTEM VALUE VALUES
            """, "(?,?,?,?,?,?,?,?,?,?,?,?)", "", rows);
    }

    private static String seatUnknownReason(AlightingAwareObservation observation) {
        if (observation.seats() == null) {
            return "NOT_REPORTED";
        }
        return null;
    }

    private static Integer knownCrowd(AlightingAwareObservation observation) {
        if (observation.crowd() > 0) {
            return observation.crowd();
        }
        return null;
    }

    private void insertCapacityHistory(WorkerReplayFixture fixture) {
        List<AlightingAwareObservation> seated = fixture.capacityEvidence().stream()
            .filter(WorkerReplayLoader::showsCapacity)
            .sorted(EVIDENCE_ORDER)
            .toList();
        Map<Evidence, Integer> known = new LinkedHashMap<>();
        String vehicle = null;
        int maximum = 0;
        for (AlightingAwareObservation observation : seated) {
            if (!observation.vehicleId().equals(vehicle)) {
                vehicle = observation.vehicleId();
                maximum = 0;
            }
            maximum = Math.max(maximum, observation.seats());
            known.put(
                new Evidence(vehicle, observation.receivedAt(), observation.batchId()),
                maximum
            );
        }
        List<List<Object>> rows = new ArrayList<>();
        known.forEach((evidence, capacity) -> rows.add(Arrays.asList(
                SOURCE_ID,
                evidence.vehicleId(),
                time(evidence.at()),
                evidence.batchId(),
                capacity
        )));
        insert("""
            INSERT INTO vehicle_capacity_history(source_id,vehicle_id,evidence_at,evidence_batch_id,capacity) VALUES
            """, "(?,?,?,?,?)", " ON CONFLICT DO NOTHING", rows);
    }

    private static boolean showsCapacity(AlightingAwareObservation observation) {
        return observation.vehicleId() != null && observation.seats() != null && observation.seats() >= 1
            && observation.seats() <= 70;
    }

    /** 여러 줄을 한 문장으로 넣는다. 줄이 많으면 문장을 나눈다. */
    private void insert(
        String head,
        String placeholders,
        String tail,
        List<List<Object>> rows
    ) {
        for (int from = 0; from < rows.size(); from += ROWS_PER_STATEMENT) {
            List<List<Object>> chunk = rows.subList(from, Math.min(from + ROWS_PER_STATEMENT, rows.size()));
            List<Object> values = new ArrayList<>();
            chunk.forEach(values::addAll);
            jdbc.sql(head + String.join(",", Collections.nCopies(chunk.size(), placeholders)) + tail)
                .params(values).update();
        }
    }

    private static OffsetDateTime time(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    /** 정원 이력 한 줄의 기본 키다. */
    private record Evidence(String vehicleId, Instant at, long batchId) {

    }
}
