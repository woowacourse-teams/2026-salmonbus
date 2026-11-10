package com.gustler.backend.api.salmon.offline;

import com.gustler.backend.api.salmon.offline.PassOutcome.Observation;
import com.gustler.backend.api.salmon.offline.PassOutcome.Seats;
import com.gustler.backend.api.salmon.offline.PassOutcome.Unknown;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 판정 시각 뒤의 차량 관측에서 실제 결과를 읽는다. 노선 판본의 관측을 처음 쓸 때 한 번 읽어 차량별로 들고 있는다.
 * 판정 입력을 만들 때는 쓰지 않는다(판정 시각 뒤의 자료다).
 */
final class ObservationTrail {
    /** 제품 정산이 도착을 기다리는 한도와 같다(ArrivalLabelResolver.LONGEST_WAIT_FOR_ARRIVAL). */
    private static final Duration LONGEST_WAIT = Duration.ofHours(2);

    private record VersionTrail(Map<String, List<Observation>> byVehicle, List<Integer> boardingStops) {
    }

    private final JdbcClient jdbc;
    private final Map<Long, VersionTrail> versions = new HashMap<>();

    ObservationTrail(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 기준 시각 뒤에 그 차량이 그 정류장을 떠난 결과. 기준 시각까지의 마지막 관측에서 추적을 시작한다.
     *
     * @param notBefore 이 시각까지의 뒤 관측은 건너뛴다(예보 계산 시각). 없으면 빈 값
     */
    PassOutcome outcomeOf(long routeVersionId, Optional<String> vehicleId, int stopSequence, Instant after,
                          Optional<Instant> notBefore) {
        if (vehicleId.isEmpty()) {
            return PassOutcome.unknown(Unknown.NO_VEHICLE_ID);
        }
        VersionTrail trail = trailOf(routeVersionId);
        List<Observation> all = trail.byVehicle().getOrDefault(vehicleId.get(), List.of());
        Optional<Observation> start = all.stream().filter(observation -> !observation.at().isAfter(after))
            .reduce((earlier, latest) -> latest);
        Instant deadline = after.plus(LONGEST_WAIT);
        List<Observation> later = all.stream()
            .filter(observation -> observation.at().isAfter(after) && !observation.at().isAfter(deadline)).toList();
        return PassOutcome.resolve(start, later, stopSequence, trail.boardingStops().stream()
            .mapToInt(Integer::intValue).filter(order -> order > stopSequence).min(), notBefore);
    }

    /**
     * 기준 시각 뒤 창 길이 안에 그 정류장을 떠나기 시작한 차량마다, 통과 순번이 그 정류장 순번과 같은 첫 관측의 좌석.
     * 차량 한 대는 한 번만 센다. 기준 시각에 이미 그 정류장을 떠난 상태였던 차량은 넣지 않는다. 그 관측과 바로 앞 관측이
     * 이어지지 않으면(앞 관측 없음, 90초 초과, 방향이 없거나 다름) 떠난 직후의 관측인지 알 수 없어 좌석을 모름으로 둔다.
     */
    List<Seats> passagesWithin(long routeVersionId, int stopSequence, Instant after, Duration window) {
        return passages(trailOf(routeVersionId).byVehicle().values(), stopSequence, after, after.plus(window));
    }

    static List<Seats> passages(Collection<List<Observation>> trails, int stopSequence, Instant after,
                                Instant until) {
        List<Seats> passages = new ArrayList<>();
        for (List<Observation> trail : trails) {
            for (int index = 0; index < trail.size(); index++) {
                Observation observation = trail.get(index);
                if (observation.at().isAfter(until)) {
                    break;
                }
                if (!observation.at().isAfter(after) || observation.passedStopOrder() != stopSequence) {
                    continue;
                }
                Observation previous = index == 0 ? null : trail.get(index - 1);
                if (previous == null || previous.passedStopOrder() < stopSequence) {
                    passages.add(continues(previous, observation) ? Seats.of(observation.remainingSeats())
                        : Seats.unknown(Unknown.LOST));
                }
                break;
            }
        }
        return passages;
    }

    private static boolean continues(Observation previous, Observation observation) {
        return previous != null && previous.direction() != null
            && previous.direction().equals(observation.direction())
            && Duration.between(previous.at(), observation.at()).compareTo(PassOutcome.LONGEST_GAP) <= 0;
    }

    private VersionTrail trailOf(long routeVersionId) {
        return versions.computeIfAbsent(routeVersionId, this::load);
    }

    private VersionTrail load(long routeVersionId) {
        Map<String, List<Observation>> byVehicle = new HashMap<>();
        jdbc.sql("""
            SELECT observation.vehicle_id, batch.response_received_at, observation.passed_stop_order,
                   observation.remaining_seats, observation.quality_direction
            FROM operational_observation observation
            JOIN observation_batch batch ON batch.id = observation.observation_batch_id
            WHERE observation.route_version_id = ? AND observation.vehicle_id IS NOT NULL
              AND batch.response_received_at IS NOT NULL
            ORDER BY batch.response_received_at, observation.id
            """).param(routeVersionId).query(row -> {
                String vehicleId = row.getString(1);
                Timestamp at = row.getTimestamp(2);
                Observation observation = new Observation(at.toInstant(), row.getInt(3),
                    row.getObject(4, Integer.class), row.getObject(5, Long.class));
                byVehicle.computeIfAbsent(vehicleId, ignored -> new ArrayList<>()).add(observation);
            });
        List<Integer> boardingStops = jdbc.sql(
            "SELECT stop_order FROM route_stop WHERE route_version_id = ? AND boarding_allowed ORDER BY stop_order")
            .param(routeVersionId).query(Integer.class).list();
        return new VersionTrail(byVehicle, boardingStops);
    }
}
