package com.gustler.backend.forecasting.infrastructure.pipeline;

import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareFeatureSnapshot;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareFeatureVector;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareObservation;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwarePassEvent;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareVehiclePrefix;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 예보 입력과 노선 상태를 흐름의 이름({@link StreamKeys})으로 옮겨 적은 뒤 SHA-256으로 줄인다. 실행마다 달라지는 DB 번호가
 * 지문에 들어가지 않게 한다. 실수는 {@link Double#doubleToLongBits}의 비트로 넣는다.
 */
final class ForecastInputDigests {
    private final StreamKeys keys;

    ForecastInputDigests(StreamKeys keys) {
        this.keys = keys;
    }

    /** 예보 차량 하나의 기준 관측, 정원, 운행 시작 관측과 정류장마다의 입력 벡터(없으면 없음 표시)를 담는다. */
    String snapshot(AlightingAwareFeatureSnapshot snapshot, AlightingAwareRouteStatistics statistics) {
        MessageDigest digest = Baseline.sha256();
        text(digest, observation(snapshot.source()));
        text(digest, "|" + snapshot.capacity() + "|"
            + keys.observation(snapshot.provenance().tripStartObservationId()));
        for (AlightingAwareRouteStatistics.Stop stop : statistics.stops()) {
            AlightingAwareFeatureVector vector = vectorOrNone(snapshot, stop.order());
            if (vector == null) {
                digest.update((byte) 0);
                continue;
            }
            digest.update((byte) 1);
            doubles(digest, vector.seat88());
            doubles(digest, vector.probability80());
        }
        return Baseline.hex(digest);
    }

    /** 목표 정류장이 예보 범위 밖이면 비어 있다. */
    static AlightingAwareFeatureVector vectorOrNone(AlightingAwareFeatureSnapshot snapshot, final int target) {
        try {
            return snapshot.forTarget(target);
        } catch (IllegalArgumentException unsupported) {
            return null;
        }
    }

    /** 순위, 보존 관측(순서대로), 차량별 누적 상태(차량 이름순), 통과 사건(순서대로)을 담는다. */
    String state(
        final long rank,
        List<AlightingAwareObservation> observations,
        Map<String, AlightingAwareVehiclePrefix> prefix,
        List<AlightingAwarePassEvent> events
    ) {
        MessageDigest digest = Baseline.sha256();
        text(digest, "rank=" + rank + "\n");
        for (AlightingAwareObservation observation : observations) {
            text(digest, observation(observation) + "\n");
        }
        text(digest, "prefix\n");
        Map<String, AlightingAwareVehiclePrefix> sorted = new TreeMap<>();
        prefix.forEach((vehicle, value) -> sorted.put(StreamKeys.vehicle(vehicle), value));
        sorted.forEach((vehicle, value) -> text(digest, vehicle + "=" + prefix(value) + "\n"));
        text(digest, "events\n");
        for (AlightingAwarePassEvent event : events) {
            text(digest, event(event) + "\n");
        }
        return Baseline.hex(digest);
    }

    /** 통과 사건의 모음을 순서와 무관하게 담는다. */
    String passes(Collection<AlightingAwarePassEvent> events) {
        MessageDigest digest = Baseline.sha256();
        events.stream().map(this::event).sorted().forEach(line -> text(digest, line + "\n"));
        return Baseline.hex(digest);
    }

    private String prefix(AlightingAwareVehiclePrefix prefix) {
        return String.join(",", Integer.toHexString(Float.floatToIntBits(prefix.capacity())),
            Baseline.hex(prefix.fullStreak()), observationOrNone(prefix.previous()), String.valueOf(prefix.entryAt()),
            keys.observation(prefix.tripId()), observationOrNone(prefix.capacityObservation()));
    }

    private String event(AlightingAwarePassEvent event) {
        return String.join(",", StreamKeys.vehicle(event.vehicleId()), keys.observation(event.tripId()),
            Integer.toString(event.stopOrder()), observation(event.arrival()), observation(event.departure()),
            String.valueOf(event.confirmedAt()), observation(event.firstDeparture()),
            observation(event.confirmation()));
    }

    private String observationOrNone(AlightingAwareObservation observation) {
        if (observation == null) {
            return "-";
        }
        return observation(observation);
    }

    private String observation(AlightingAwareObservation observation) {
        return String.join("/", keys.observation(observation.observationId()),
            Integer.toString(keys.batch(observation.batchId())), Long.toString(observation.batchRank()),
            String.valueOf(observation.receivedAt()), StreamKeys.vehicle(observation.vehicleId()),
            Integer.toString(observation.passedStopOrder()), Integer.toString(observation.stopOrder()),
            Integer.toString(observation.state()), String.valueOf(observation.seats()),
            Integer.toString(observation.crowd()), Integer.toString(keys.version(observation.routeVersionId())),
            String.valueOf(observation.sourceReceivedAt()), Boolean.toString(observation.down()));
    }

    private static void text(MessageDigest digest, String text) {
        digest.update(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void doubles(MessageDigest digest, double[] values) {
        ByteBuffer bytes = ByteBuffer.allocate(values.length * Long.BYTES);
        for (final double value : values) {
            bytes.putLong(Double.doubleToLongBits(value));
        }
        digest.update(bytes.array());
    }
}
