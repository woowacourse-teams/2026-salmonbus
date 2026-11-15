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
import java.sql.Types;
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
 * 번호판(plate_number)은 받지 않는다. 규격에 없는 열이 있으면 적재를 멈춘다. 예보 기록의 배열 열은 COPY가 쓰는
 * PostgreSQL 배열 표기({@code {15,16}}, 빈 원소는 {@code NULL}) 그대로 받는다.
 */
final class InputFolder {
    static final String DECISION_FILE = "decision";
    private static final String FIRST_PUBLICATION = "route_version_first_publication";
    /** 제품이 표를 없앤 뒤에도 예전 추출 폴더에 남을 수 있는 파일. 있으면 적재를 멈춘다. */
    private static final List<String> RETIRED_FILES = List.of("ineligible_observation", "route_data_quality");
    /** 예보 입력이 예보 기록(forecast_run, forecast_record)으로 바뀌기 전의 예보 파일. 있으면 적재를 멈춘다. */
    private static final List<String> PRE_RECORD_FORECAST_FILES = List.of("forecast_publication", "seat_forecast");
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
        new Table("forecast_run", false,
            List.of("source_date", "observation_batch_id", "route_id", "route_version_id", "received_at",
                "confirmed_at", "outcome", "prediction_count"),
            List.of("model_deployment_id", "source_incomplete", "counted_rows")),
        new Table("forecast_record", false,
            List.of("source_date", "vehicle_observation_id", "observation_batch_id", "source_row_number", "route_id",
                "route_version_id", "vehicle_id", "passed_stop_order", "trip_start_observation_id",
                "chain_start_observation_id", "down", "capacity", "extended", "target_stop_orders", "expected_seats",
                "full_probabilities"),
            List.of()),
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
        List<String> retired = RETIRED_FILES.stream().filter(name -> fileOf(folder, name).isPresent()).toList();
        if (!retired.isEmpty()) {
            throw new IllegalArgumentException(String.join(", ", retired)
                + " 파일은 받지 않는다. 제품이 그 표와 예보 적격 뷰를 더 이상 읽지 않는다");
        }
        rejectPreRecordForecasts(folder);
        Map<String, Integer> loaded = new LinkedHashMap<>();
        for (Table table : TABLES) {
            Optional<Path> file = fileOf(folder, table.name());
            if (file.isEmpty() && table.mandatory()) {
                throw new IllegalArgumentException("입력 폴더에 " + table.name() + ".csv(.gz)가 있어야 한다");
            }
            loaded.put(table.name(), file.isEmpty() ? 0 : insert(connection, table, file.get()));
        }
        Optional<Path> firstPublication = fileOf(folder, FIRST_PUBLICATION);
        loaded.put(FIRST_PUBLICATION, firstPublication.isEmpty() ? 0
            : markFirstPublications(connection, firstPublication.get()));
        return loaded;
    }

    private static void rejectPreRecordForecasts(Path folder) {
        List<String> found = PRE_RECORD_FORECAST_FILES.stream().filter(name -> fileOf(folder, name).isPresent())
            .toList();
        if (found.isEmpty()) {
            return;
        }
        throw new IllegalArgumentException(String.join(", ", found) + " 파일은 읽지 않는다. 예보 입력은 예보 기록 "
            + "forecast_run.csv(.gz)와 forecast_record.csv(.gz)로 바뀌었다. 이 폴더는 예보 기록으로 바뀌기 전의 형식이라 "
            + "그대로 돌리면 예보 없이 판정하게 된다. 같은 구간을 지금의 추출 스크립트로 다시 뽑는다");
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

    private static Map<String, String> snapshotContract(Map<String, String> row) {
        Map<String, String> filled = new LinkedHashMap<>();
        filled.put("provider", ArrivalSnapshot.PROVIDER);
        filled.put("operation", ArrivalSnapshot.OPERATION);
        filled.put("normalization_version", ArrivalSnapshot.NORMALIZATION_VERSION);
        filled.put("payload_digest", digestOf(row.get("payload")));
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
     * 입력에는 판정 구간의 예보 실행 줄만 들어 있다. 그 전에 이미 실행 줄이 있던 판본을 "발행이 한 번도 없는 판본"으로 읽지
     * 않도록, 판본의 첫 착수 시각에 예측 없는 표시용 실행 줄(EMPTY)을 하나 넣는다. 묶음의 수신 시각을 하루 앞에 둬서 보드가
     * 고르는 일은 없다. 입력에 그 시각 이전의 실행 줄이 이미 있으면 넣지 않는다.
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
                    SELECT 1 FROM forecast_run run
                    WHERE run.route_version_id = version.id
                      AND run.confirmed_at <= CAST(? AS timestamptz))
                """);
             PreparedStatement run = connection.prepareStatement("""
                INSERT INTO forecast_run(source_date, observation_batch_id, route_id, route_version_id, received_at,
                    confirmed_at, outcome, prediction_count)
                SELECT (marker.scheduled_at AT TIME ZONE 'Asia/Seoul')::date, marker.id, version.route_id,
                    marker.route_version_id, marker.response_received_at, CAST(? AS timestamptz), 'EMPTY', 0
                FROM observation_batch marker
                JOIN route_version version ON version.id = marker.route_version_id
                WHERE marker.id = -CAST(? AS bigint)
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
                    run.setString(1, publishedAt);
                    run.setString(2, version);
                    run.executeUpdate();
                    marked++;
                }
            }
        }
        return marked;
    }
}
