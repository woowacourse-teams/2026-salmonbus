package com.gustler.backend.api.salmon.offline;

import com.gustler.backend.arrival.contract.ArrivalSnapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 입력 폴더의 규격과 적재. 파일은 테이블 이름에 .csv 또는 .csv.gz를 붙인 이름이고 첫 줄은 열 이름이다.
 *
 * <p>운영 DB에서 {@code COPY (SELECT ...) TO STDOUT WITH (FORMAT csv, HEADER)}로 뽑은 모양을 그대로 받는다.
 * 번호판(plate_number)과 예보의 특징 값(feature_snapshot)은 받지 않는다. 규격에 없는 열이 있으면 적재를 멈춘다.
 */
final class InputFolder {
    static final String DECISION_FILE = "decision";
    private static final String INELIGIBLE_OBSERVATION = "ineligible_observation";
    private static final String FIRST_PUBLICATION = "route_version_first_publication";
    private static final int BATCH_SIZE = 1_000;

    record Table(String name, boolean mandatory, List<String> required, List<String> optional,
                 Function<Map<String, String>, Map<String, String>> filled) {
        Table(String name, boolean mandatory, List<String> required, List<String> optional) {
            this(name, mandatory, required, optional, row -> Map.of());
        }
    }

    /** 외래 키가 걸리는 순서다. */
    static final List<Table> TABLES = List.of(
        new Table("route", true,
            List.of("id", "public_route_id", "source_id", "source_route_id", "display_name", "start_stop_name",
                "end_stop_name"), List.of()),
        new Table("route_version", true,
            List.of("id", "route_id", "content_digest", "valid_from"),
            List.of("valid_to", "turn_sequence", "up_first_departure_time", "up_last_departure_time",
                "down_first_departure_time", "down_last_departure_time", "weekday_min_headway_minutes",
                "weekday_max_headway_minutes", "saturday_min_headway_minutes", "saturday_max_headway_minutes",
                "sunday_min_headway_minutes", "sunday_max_headway_minutes", "holiday_min_headway_minutes",
                "holiday_max_headway_minutes")),
        new Table("route_stop", true,
            List.of("route_version_id", "stop_order", "stop_id", "name", "direction", "boarding_allowed"),
            List.of()),
        new Table("route_data_quality", false, List.of("route_id"), List.of("quality_revision")),
        new Table("model_deployment", false,
            List.of("id", "deployment_key", "release_id", "model_key", "model_version", "bundle_digest",
                "prediction_target_version", "calculation_version", "supported_scope_digest", "data_until", "state"),
            List.of("predecessor_deployment_id", "activated_at", "retired_at")),
        new Table("observation_batch", false,
            List.of("id", "route_version_id", "scheduled_at", "attempt_number", "attempt_key", "outcome",
                "normalization_version", "collection_strategy_version"),
            List.of("requested_at", "response_received_at", "completed_at", "http_status", "result_code",
                "failure_code", "provider_rows", "stored_rows", "excluded_rows", "input_confirmed_at",
                "ingestion_origin")),
        new Table("vehicle_observation", false,
            List.of("id", "observation_batch_id", "route_version_id", "source_row_number", "stop_order", "stop_id",
                "running_state", "passed_stop_order"),
            List.of("vehicle_id", "vehicle_trip_key", "remaining_seats", "crowd_level", "vehicle_type", "route_type",
                "tagless", "seat_unknown_reason")),
        new Table("forecast_publication", false,
            List.of("id", "source_batch_id", "route_version_id", "observed_at", "published_at", "prediction_count"),
            List.of("model_deployment_id", "demand_statistics_revision", "quality_revision", "generated_at",
                "provenance")),
        new Table("seat_forecast", false,
            List.of("vehicle_observation_id", "target_stop_order", "route_version_id", "stops_to_target",
                "model_deployment_id", "demand_statistics_revision", "seat_full_chance_raw", "seat_full_chance",
                "generated_at", "publication_id"),
            List.of("expected_seats", "quality_revision", "evaluation_detail_required", "seat_target",
                "probability_target", "label_version", "inference_bundle_hash"),
            InputFolder::forecastFeatureSnapshot),
        new Table("arrival_snapshot", false,
            List.of("id", "station_id", "received_at", "refresh_after_at", "stale_at", "payload"),
            List.of("stored_at"), InputFolder::snapshotContract));

    private InputFolder() {
    }

    /** 테이블별 적재 행 수를 돌려준다. 파일이 없는 선택 테이블은 0이다. */
    static Map<String, Integer> load(Connection connection, Path folder) throws IOException, SQLException {
        if (Files.exists(folder.resolve("INCOMPLETE"))) {
            throw new IllegalArgumentException("입력 폴더에 INCOMPLETE 파일이 있다. 추출이 중간에 멈춘 폴더라 쓰지 않는다");
        }
        Map<String, Integer> loaded = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement()) {
            // 하차 뒤 좌석 예보를 넣을 때마다 평가 작업 행을 만드는 트리거다. 실행기는 평가를 돌리지 않는다.
            statement.execute("ALTER TABLE seat_forecast DISABLE TRIGGER trg_les_evaluation_work");
        }
        for (Table table : TABLES) {
            Optional<Path> file = fileOf(folder, table.name());
            if (file.isEmpty() && table.mandatory()) {
                throw new IllegalArgumentException("입력 폴더에 " + table.name() + ".csv(.gz)가 있어야 한다");
            }
            loaded.put(table.name(), file.isEmpty() ? 0 : insert(connection, table, file.get()));
        }
        Optional<Path> ineligible = fileOf(folder, INELIGIBLE_OBSERVATION);
        loaded.put(INELIGIBLE_OBSERVATION, ineligible.isEmpty() ? 0 : exclude(connection, ineligible.get()));
        Optional<Path> firstPublication = fileOf(folder, FIRST_PUBLICATION);
        loaded.put(FIRST_PUBLICATION, firstPublication.isEmpty() ? 0
            : markFirstPublications(connection, firstPublication.get()));
        return loaded;
    }

    static Optional<Path> fileOf(Path folder, String name) {
        return Stream.of(name + ".csv", name + ".csv.gz").map(folder::resolve).filter(Files::isRegularFile)
            .findFirst();
    }

    private static int insert(Connection connection, Table table, Path file) throws IOException, SQLException {
        try (CsvRows rows = CsvRows.open(file)) {
            List<String> columns = headerOf(rows, table, file);
            List<String> first = rows.next();
            if (first == null) {
                return 0;
            }
            List<String> filledColumns = List.copyOf(table.filled().apply(rowOf(columns, first, file)).keySet());
            List<String> all = Stream.concat(columns.stream(), filledColumns.stream()).toList();
            String sql = "INSERT INTO " + table.name() + " (" + String.join(", ", all) + ") OVERRIDING SYSTEM VALUE"
                + " VALUES (" + all.stream().map(column -> "?").collect(Collectors.joining(", ")) + ")";
            int count = 0;
            try (PreparedStatement insert = connection.prepareStatement(sql)) {
                for (List<String> row = first; row != null; row = rows.next()) {
                    Map<String, String> values = rowOf(columns, row, file);
                    values.putAll(table.filled().apply(values));
                    int index = 1;
                    for (String column : all) {
                        bind(insert, index++, values.get(column));
                    }
                    insert.addBatch();
                    count++;
                    if (count % BATCH_SIZE == 0) {
                        insert.executeBatch();
                    }
                }
                insert.executeBatch();
            }
            return count;
        }
    }

    private static List<String> headerOf(CsvRows rows, Table table, Path file) throws IOException {
        List<String> header = rows.next();
        if (header == null) {
            throw new IllegalArgumentException(file.getFileName() + "에 열 이름 줄이 없다");
        }
        List<String> allowed = Stream.concat(table.required().stream(), table.optional().stream()).toList();
        List<String> unknown = header.stream().filter(column -> !allowed.contains(column)).toList();
        List<String> missing = table.required().stream().filter(column -> !header.contains(column)).toList();
        if (!unknown.isEmpty() || !missing.isEmpty()) {
            throw new IllegalArgumentException(file.getFileName() + "의 열이 규격과 다르다. 규격에 없는 열 " + unknown
                + ", 빠진 필수 열 " + missing);
        }
        return header;
    }

    private static Map<String, String> rowOf(List<String> columns, List<String> row, Path file) {
        if (row.size() != columns.size()) {
            throw new IllegalArgumentException(file.getFileName() + "에 칸 수가 열 수와 다른 행이 있다");
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < columns.size(); index++) {
            values.put(columns.get(index), row.get(index));
        }
        return values;
    }

    private static void bind(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.NULL);
        } else {
            statement.setString(index, value);
        }
    }

    // 하차 뒤 좌석 예보 행은 특징 값이 비어 있으면 DB 제약에 걸린다. 실행기는 특징 값을 읽지 않아 빈 객체로 채운다.
    private static Map<String, String> forecastFeatureSnapshot(Map<String, String> row) {
        return "AFTER_ALIGHTING_ESTIMATE".equals(row.get("seat_target"))
            ? Map.of("feature_snapshot", "{}") : nullOf("feature_snapshot");
    }

    private static Map<String, String> snapshotContract(Map<String, String> row) {
        Map<String, String> filled = new LinkedHashMap<>();
        filled.put("provider", ArrivalSnapshot.PROVIDER);
        filled.put("operation", ArrivalSnapshot.OPERATION);
        filled.put("normalization_version", ArrivalSnapshot.NORMALIZATION_VERSION);
        filled.put("payload_digest", digestOf(row.get("payload")));
        return filled;
    }

    private static Map<String, String> nullOf(String column) {
        Map<String, String> filled = new LinkedHashMap<>();
        filled.put(column, null);
        return filled;
    }

    private static String digestOf(String payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * 입력에는 판정 구간의 발행만 들어 있다. 그 전에 이미 발행이 있던 판본을 "발행이 한 번도 없는 판본"으로 읽지 않도록,
     * 판본의 첫 발행 시각에 예보 없는 표시용 발행을 하나 넣는다. 묶음의 관측 시각을 하루 앞에 둬서 보드가 고르는 일은 없다.
     * 입력에 그 시각 이전의 발행이 이미 있으면 넣지 않는다.
     */
    private static int markFirstPublications(Connection connection, Path file) throws IOException, SQLException {
        int marked = 0;
        try (CsvRows rows = CsvRows.open(file);
             PreparedStatement batch = connection.prepareStatement("""
                INSERT INTO observation_batch(id, route_version_id, scheduled_at, attempt_number, attempt_key,
                    response_received_at, outcome, stored_rows, normalization_version, collection_strategy_version)
                OVERRIDING SYSTEM VALUE
                SELECT -version.id, version.id, CAST(? AS timestamptz) - interval '1 day', 1,
                    'offline-first-publication-marker', CAST(? AS timestamptz) - interval '1 day', 'SUCCESS_EMPTY', 0,
                    'offline-marker', 'offline-marker'
                FROM route_version version
                WHERE version.id = CAST(? AS bigint) AND NOT EXISTS (
                    SELECT 1 FROM forecast_publication publication
                    WHERE publication.route_version_id = version.id
                      AND publication.published_at <= CAST(? AS timestamptz))
                """);
             PreparedStatement publication = connection.prepareStatement("""
                INSERT INTO forecast_publication(id, source_batch_id, route_version_id, observed_at, published_at,
                    prediction_count, provenance)
                OVERRIDING SYSTEM VALUE
                SELECT marker.id, marker.id, marker.route_version_id, marker.response_received_at,
                    CAST(? AS timestamptz), 0, 'LEGACY_UNKNOWN'
                FROM observation_batch marker WHERE marker.id = -CAST(? AS bigint)
                """)) {
            headerOf(rows, new Table(FIRST_PUBLICATION, false, List.of("route_version_id", "first_published_at"),
                List.of()), file);
            for (List<String> row = rows.next(); row != null; row = rows.next()) {
                Map<String, String> values = rowOf(List.of("route_version_id", "first_published_at"), row, file);
                String version = values.get("route_version_id");
                String publishedAt = values.get("first_published_at");
                batch.setString(1, publishedAt);
                batch.setString(2, publishedAt);
                batch.setString(3, version);
                batch.setString(4, publishedAt);
                if (batch.executeUpdate() == 1) {
                    publication.setString(1, publishedAt);
                    publication.setString(2, version);
                    publication.executeUpdate();
                    marked++;
                }
            }
        }
        return marked;
    }

    /**
     * 예보 적격에서 빠진 관측을 제품 뷰(forecast_eligible_observation)가 빼도록 제외 상태의 편도를 붙인다.
     * 뷰가 보는 테이블에 그대로 넣어서, 실행기 읽기와 제품 읽기가 같은 행을 뺀다.
     */
    private static int exclude(Connection connection, Path file) throws IOException, SQLException {
        List<String> ids = new ArrayList<>();
        try (CsvRows rows = CsvRows.open(file)) {
            headerOf(rows, new Table(INELIGIBLE_OBSERVATION, false, List.of("vehicle_observation_id"), List.of()),
                file);
            for (List<String> row = rows.next(); row != null; row = rows.next()) {
                ids.add(row.get(0));
            }
        }
        try (PreparedStatement trip = connection.prepareStatement("""
                INSERT INTO vehicle_one_way_trip(id, start_observation_id, route_version_id, vehicle_id, status,
                    boundary, rule_version)
                SELECT 'offline-excluded-' || id, id, route_version_id, vehicle_id, 'EXCLUDED', 'OFFLINE_INPUT',
                    'offline-input'
                FROM vehicle_observation WHERE id = CAST(? AS bigint)
                """);
             PreparedStatement assignment = connection.prepareStatement("""
                INSERT INTO observation_trip_assignment(observation_id, trip_id)
                SELECT id, 'offline-excluded-' || id FROM vehicle_observation WHERE id = CAST(? AS bigint)
                """)) {
            for (String id : ids) {
                trip.setString(1, id);
                trip.addBatch();
                assignment.setString(1, id);
                assignment.addBatch();
            }
            trip.executeBatch();
            assignment.executeBatch();
        }
        return ids.size();
    }
}
