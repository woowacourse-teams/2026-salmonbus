package com.gustler.backend.archive;

import java.util.List;
import java.util.stream.IntStream;

/** SQL 식별자는 이 목록에서만 선택한다. 값은 항상 bind한다. 순서는 FK 삭제 순서다. */
public enum ArchiveTable {
    LES_RESULT("les_forecast_evaluation_result", List.of("vehicle_observation_id", "target_stop_order",
        "target_kind", "label_version", "quality_revision"), "observation"),
    LES_ARCHIVE("les_evaluation_archive", List.of("vehicle_observation_id", "target_stop_order",
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
        List<String> cursors = IntStream.range(0, keys.size())
            .mapToObj(index -> "COALESCE((CAST(:cursor AS jsonb)->>" + index + ")::" + keyType(keys.get(index))
                + "," + (keyType(keys.get(index)).equals("text") ? "''" : "0") + ")").toList();
        return "(" + keyOrder() + ")>(" + String.join(",", cursors) + ")";
    }

    String selection() {
        return switch (scope) {
        case "observation" -> "JOIN vehicle_observation o ON o.id=t.vehicle_observation_id JOIN"
            + " observation_batch b ON b.id=o.observation_batch_id";
        case "source_batch" -> "JOIN observation_batch b ON b.id=t.source_batch_id";
        case "self" -> "JOIN observation_batch b ON b.id=t.observation_batch_id";
        default -> "";
        };
    }

    String batch() {
        if (scope.equals("batch")) {
            return "t";
        }
        return "b";
    }

    private static String keyType(String key) {
        return key.equals("target_kind") || key.equals("label_version") ? "text" : "bigint";
    }

    String keyMatch() {
        return String.join(" AND ", keys.stream()
            .map(key -> "t." + key + "=(r.original->>'" + key + "')::" + keyType(key)).toList());
    }
}
