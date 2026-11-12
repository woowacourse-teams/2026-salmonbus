package com.gustler.backend.archive;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/** SQL 식별자는 이 목록에서만 선택한다. 값은 항상 bind한다. 순서는 FK 삭제 순서다. */
public enum ArchiveTable {
    ALIGHTING_AWARE_RESULT("alighting_forecast_evaluation_result", List.of("vehicle_observation_id", "target_stop_order",
        "target_kind", "label_version", "quality_revision"), "observation"),
    ALIGHTING_AWARE_ARCHIVE("alighting_evaluation_archive", List.of("vehicle_observation_id", "target_stop_order",
        "target_kind", "label_version", "quality_revision"), "observation"),
    RESULT("forecast_evaluation_result", List.of("vehicle_observation_id", "target_stop_order"), "observation"),
    COMPACT("forecast_evaluation_compact", List.of("vehicle_observation_id", "target_stop_order"), "observation"),
    LEGACY_MEMBER("archive_legacy_member", List.of("vehicle_observation_id", "target_stop_order"), "observation"),
    FORECAST("seat_forecast", List.of("vehicle_observation_id", "target_stop_order"), "observation"),
    PUBLICATION("forecast_publication", List.of("id"), "source_batch"),
    OBSERVATION("vehicle_observation", List.of("id"), "self"), BATCH("observation_batch", List.of("id"), "batch");

    final String table;
    final List<String> keys;
    final String scope;

    ArchiveTable(String table, List<String> keys, String scope) {
        this.table = table;
        this.keys = keys;
        this.scope = scope;
    }

    String key() {
        return "jsonb_build_array(" + String.join(",", keys.stream().map(k -> "t." + k).toList()) + ")";
    }

    String keyOrder() {
        return String.join(",", keys.stream().map(key -> "t." + key).toList());
    }

    String afterCursor() {
        List<String> cursors = IntStream.range(0, keys.size()).mapToObj(this::cursor).toList();
        return "(" + keyOrder() + ")>(" + String.join(",", cursors) + ")";
    }

    private String cursor(final int index) {
        return "COALESCE((CAST(:cursor AS jsonb)->>" + index + ")::" + keyType(keys.get(index)) + ","
            + (keyType(keys.get(index)).equals("text") ? "''" : "0") + ")";
    }

    /**
     * 페이지를 이끄는 id 목록을 별칭 {@code x} 의 {@code id} 열로 내는 하위 질의다. 바깥에 {@code day_batches} CTE 가 있어야 한다.
     *
     * <p>관측에 딸린 표와 관측 표는 그 노선·날짜 배치의 관측을, 배치 표와 발행 표는 그 배치를 쓴다. 첫 키가 이 id 와 같은 표는 id 순서로
     * 정렬하고 커서의 첫 키보다 작은 id 를 미리 거른다. 관측은 배치마다 {@code LATERAL} 로 (observation_batch_id, source_row_number)
     * 유일 키를 따라가게 하고, {@code OFFSET 0} 으로 이 하위 질의가 펼쳐져 관측 표 쪽에서 출발하는 계획이 되지 않게 한다.
     */
    String driver() {
        return switch (scope) {
        case "observation", "self" -> "SELECT o.id FROM day_batches d CROSS JOIN LATERAL (SELECT o.id"
            + " FROM vehicle_observation o WHERE o.observation_batch_id=d.id AND o.id>=" + cursor(0)
            + " OFFSET 0) o ORDER BY o.id";
        case "batch" -> "SELECT d.id FROM day_batches d WHERE d.id>=" + cursor(0) + " ORDER BY d.id";
        default -> "SELECT d.id FROM day_batches d";
        };
    }

    /** {@link #driver()} 의 {@code x.id} 로 이 표({@code t})의 행을 찾는 조건이다. 각 조건은 이 표의 기본 키나 유일 키 앞부분이다. */
    String link() {
        return switch (scope) {
        case "observation" -> "t.vehicle_observation_id=x.id";
        case "source_batch" -> "t.source_batch_id=x.id";
        default -> "t.id=x.id";
        };
    }

    /**
     * 페이지 정렬이다. 하위 질의 {@code r} 이 키 열을 낸다고 본다.
     *
     * <p>첫 키가 {@code x.id} 와 같은 표는 첫 키 대신 {@code x.id} 로 정렬한다. 값이 같아 순서는 키 순서 그대로이고, 이미 정렬된 첫 키를
     * 계획기가 알아보므로 첫 키가 같은 행끼리만 정렬하고 {@code LIMIT} 을 채우면 멈춘다.
     */
    String pageOrder() {
        List<String> order = new ArrayList<>(keys.stream().map(key -> "r." + key).toList());
        if (!scope.equals("source_batch")) {
            order.set(0, "x.id");
        }
        return String.join(",", order);
    }

    private static String keyType(String key) {
        return key.equals("target_kind") || key.equals("label_version") ? "text" : "bigint";
    }

    String keyMatch() {
        return String.join(" AND ", keys.stream()
            .map(key -> "t." + key + "=(r.original->>'" + key + "')::" + keyType(key)).toList());
    }
}
