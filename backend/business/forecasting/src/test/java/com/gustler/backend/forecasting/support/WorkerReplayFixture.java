package com.gustler.backend.forecasting.support;

import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareObservation;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

/**
 * 재생 자료의 노선 파일 하나다.
 *
 * <p>파일은 하루치 관측({@code observations})과 배치 장부({@code batches}), 노선 버전과 정류장, 그날 앞의 차량별 마지막
 * 관측({@code prefix}), 독립 계산한 기대값을 담은 사례({@code cases})로 이루어진다. 관측의 {@code received_at}은 그 배치의 착수
 * 시각이고 {@code physical_received_at}은 응답 수신 시각이다. 장부에는 그날 앞의 관측이 속한 배치도 들어 있으나, 재생하는 배치는
 * 그날 관측이 있는 배치뿐이다.
 */
final class WorkerReplayFixture {

    private final JsonNode root;
    private final Map<Long, AlightingAwareObservation> observations;
    private final List<Batch> batches;

    private WorkerReplayFixture(
        JsonNode root,
        Map<Long, AlightingAwareObservation> observations,
        List<Batch> batches
    ) {
        this.root = root;
        this.observations = observations;
        this.batches = batches;
    }

    static WorkerReplayFixture of(JsonNode root) {
        Map<Long, AlightingAwareObservation> observations = new LinkedHashMap<>();
        for (JsonNode row : root.path("observations")) {
            AlightingAwareObservation observation = observation(row);
            observations.put(observation.observationId(), observation);
        }
        return new WorkerReplayFixture(root, observations, replayedBatches(root, observations.values()));
    }

    private static List<Batch> replayedBatches(
        JsonNode root,
        Collection<AlightingAwareObservation> observations
    ) {
        Set<Long> observed = observations.stream()
            .map(AlightingAwareObservation::batchId)
            .collect(Collectors.toSet());
        List<Batch> batches = new ArrayList<>();
        for (JsonNode row : root.path("batches")) {
            if (observed.remove(row.path("id").asLong())) {
                batches.add(batch(row));
            }
        }
        if (!observed.isEmpty()) {
            throw new IllegalArgumentException("재생 자료의 배치 장부에 그날 관측의 배치가 없다: " + observed.size());
        }
        batches.sort(
            Comparator.comparing(Batch::confirmedAt)
                .thenComparingLong(Batch::id)
        );
        return List.copyOf(batches);
    }

    private static Batch batch(JsonNode row) {
        return new Batch(
                row.path("id").asLong(),
                row.path("rv").asLong(),
                Instant.parse(row.path("confirmed_at").asString()),
                Instant.parse(row.path("response_at").asString()),
                row.path("stored_rows").asInt()
        );
    }

    /** 모델 계수의 노선 이름이다. 노선의 표시 이름으로 넣는다. */
    String route() {
        return root.path("route").asString();
    }

    /** 수집 출처의 노선 ID다. */
    String sourceRouteId() {
        return root.path("source_route_id").asString();
    }

    List<Version> versions() {
        List<Version> versions = new ArrayList<>();
        for (JsonNode row : root.path("route_versions")) {
            versions.add(new Version(
                    row.path("id").asLong(),
                    row.path("turn_sequence").asInt(),
                    row.path("content_digest").asString(),
                    time(row.path("valid_from").asString()),
                    nullableTime(row.path("valid_to"))
            ));
        }
        return versions;
    }

    List<Stop> stops() {
        List<Stop> stops = new ArrayList<>();
        for (JsonNode row : root.path("statistics").path("route_metadata")) {
            stops.add(new Stop(
                    row.path("order").asInt(),
                    row.path("id").asString(),
                    row.path("name").asString(),
                    row.path("direction").asString(),
                    row.path("boardingAllowed").asBoolean()
            ));
        }
        return stops;
    }

    /** 재생할 배치다. (처리 시작 시각, 배치 번호) 순서다. */
    List<Batch> batches() {
        return batches;
    }

    /** 그날의 관측이다. 파일에 기록된 순서다. */
    Collection<AlightingAwareObservation> observations() {
        return observations.values();
    }

    AlightingAwareObservation observation(final long observationId) {
        AlightingAwareObservation observation = observations.get(observationId);
        if (observation == null) {
            throw new IllegalArgumentException("재생 자료의 사례가 가리키는 관측이 그날 관측에 없다: " + observationId);
        }
        return observation;
    }

    /** 차량 정원의 근거가 되는 관측이다. 그날의 관측에 그날 앞의 차량별 마지막 관측과 정원을 올린 관측을 더한다. */
    List<AlightingAwareObservation> capacityEvidence() {
        Map<Long, AlightingAwareObservation> evidence = new LinkedHashMap<>(observations);
        for (JsonNode state : root.path("prefix")) {
            AlightingAwareObservation previous = observation(state.path("previous"));
            evidence.put(previous.observationId(), previous);
            if (state.has("capacity_observation")) {
                AlightingAwareObservation capacity = observation(state.path("capacity_observation"));
                evidence.put(capacity.observationId(), capacity);
            }
        }
        return List.copyOf(evidence.values());
    }

    /** 사례다. 기준 관측의 처리 시작 시각 순서다. */
    List<JsonNode> cases() {
        List<JsonNode> cases = new ArrayList<>();
        root.path("cases").forEach(cases::add);
        cases.sort(Comparator.comparing(row -> observation(row.path("source_id").asLong()).receivedAt()));
        return cases;
    }

    private static AlightingAwareObservation observation(JsonNode row) {
        return new AlightingAwareObservation(
                row.path("id").asLong(),
                row.path("batch_id").asLong(),
                row.path("batch_rank").asLong(),
                Instant.parse(row.path("received_at").asString()),
                row.path("vehicle_id").asString(),
                row.path("passed_stop_order").asInt(),
                row.path("stop_order").asInt(),
                row.path("running_state").asInt(),
                seats(row.path("remaining_seats")),
                row.path("crowd_level").asInt(),
                row.path("route_version_id").asLong(),
                Instant.parse(row.path("physical_received_at").asString()),
                row.path("down").asBoolean()
        );
    }

    private static Integer seats(JsonNode value) {
        if (value.isNull()) {
            return null;
        }
        return value.asInt();
    }

    /** {@code 2026-10-01 00:00:00+00}처럼 DB가 내보낸 표기도 읽는다. */
    private static OffsetDateTime time(String value) {
        String normalized = value.replace(' ', 'T');
        if (normalized.endsWith("+00")) {
            normalized += ":00";
        }
        return OffsetDateTime.parse(normalized);
    }

    private static OffsetDateTime nullableTime(JsonNode value) {
        if (value.isNull()) {
            return null;
        }
        return time(value.asString());
    }

    /** 노선 버전 한 행이다. 유효 기간의 끝은 비어 있을 수 있다. */
    record Version(
        long id,
        int turnSequence,
        String contentDigest,
        OffsetDateTime validFrom,
        OffsetDateTime validTo
    ) {

    }

    /** 통계에 기록된 정류장 한 행이다. 버전마다 같은 구성으로 넣는다. */
    record Stop(
        int order,
        String id,
        String name,
        String direction,
        boolean boardingAllowed
    ) {

    }

    /** 재생할 배치 한 행이다. 수신 시각은 응답 수신 시각이다. */
    record Batch(
        long id,
        long routeVersionId,
        Instant confirmedAt,
        Instant responseAt,
        int storedRows
    ) {

    }
}
