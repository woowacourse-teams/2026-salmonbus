package com.gustler.backend.forecasting.infrastructure.pipeline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 한 흐름의 DB 번호를 실행마다 같은 이름으로 바꾼다. 배치는 넣은 순서의 순번, 관측은 {@code 배치 순번.원본 줄 번호},
 * 노선 판본은 넣은 순서의 순번, 차량은 흐름마다 붙는 임의 접두를 뗀 이름이다. 고정 기대값의 키로 쓴다.
 */
final class StreamKeys {
    private static final Pattern VEHICLE = Pattern.compile("^[0-9a-f]{8}-(.+)$");

    private final List<Long> batchIds;
    private final Map<Long, Integer> batchOrdinals = new HashMap<>();
    private final Map<Long, String> observationKeys = new HashMap<>();
    private final Map<String, Long> observationIds = new HashMap<>();
    private final Map<Long, Integer> versionOrdinals = new HashMap<>();

    private StreamKeys(List<Long> batchIds) {
        this.batchIds = List.copyOf(batchIds);
    }

    static StreamKeys of(JdbcClient jdbc, ObservationStreamFixture.Stream stream) {
        List<Long> versions = new ArrayList<>(stream.stopsByVersion().keySet());
        List<Long> batches = jdbc.sql(
                "SELECT id FROM observation_batch WHERE route_version_id IN (:versions) ORDER BY id")
            .param("versions", versions).query(Long.class).list();
        StreamKeys keys = new StreamKeys(batches);
        for (int ordinal = 0; ordinal < batches.size(); ordinal++) {
            keys.batchOrdinals.put(batches.get(ordinal), ordinal);
        }
        versions.stream().sorted().forEach(version -> keys.versionOrdinals.put(version, keys.versionOrdinals.size()));
        jdbc.sql("""
            SELECT id,observation_batch_id,source_row_number FROM vehicle_observation
            WHERE route_version_id IN (:versions)
            """).param("versions", versions).query(row -> {
                String key = keys.batchOrdinals.get(row.getLong(2)) + "." + row.getInt(3);
                keys.observationKeys.put(row.getLong(1), key);
                keys.observationIds.put(key, row.getLong(1));
            });
        return keys;
    }

    /** 배치의 순번. 흐름에 없는 배치면 예외다. */
    int batch(final long batchId) {
        Integer ordinal = batchOrdinals.get(batchId);
        if (ordinal == null) {
            throw new IllegalArgumentException("흐름에 없는 배치: " + batchId);
        }
        return ordinal;
    }

    long batchId(final int ordinal) {
        return batchIds.get(ordinal);
    }

    /** 관측의 이름. 번호가 0 이하면 번호 그대로이고, 흐름에 없는 양수 번호면 예외다. */
    String observation(final long observationId) {
        if (observationId <= 0) {
            return Long.toString(observationId);
        }
        String key = observationKeys.get(observationId);
        if (key == null) {
            throw new IllegalArgumentException("흐름에 없는 관측: " + observationId);
        }
        return key;
    }

    /** 비어 있을 수 있는 관측 번호의 이름. 비어 있으면 {@code -}다. */
    String observationOrNone(Long observationId) {
        if (observationId == null) {
            return "-";
        }
        return observation(observationId);
    }

    long observationId(String key) {
        Long id = observationIds.get(key);
        if (id == null) {
            throw new IllegalArgumentException("흐름에 없는 관측 이름: " + key);
        }
        return id;
    }

    int version(final long routeVersionId) {
        Integer ordinal = versionOrdinals.get(routeVersionId);
        if (ordinal == null) {
            throw new IllegalArgumentException("흐름에 없는 노선 판본: " + routeVersionId);
        }
        return ordinal;
    }

    static String vehicle(String vehicleId) {
        Matcher matcher = VEHICLE.matcher(vehicleId);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        return vehicleId;
    }
}
