package com.gustler.backend.api.salmon.offline;

import com.gustler.backend.arrival.contract.ArrivalSnapshot;
import com.gustler.backend.arrival.contract.ArrivalSnapshot.Prediction;
import com.gustler.backend.arrival.contract.ArrivalSnapshot.RouteArrival;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import tools.jackson.databind.ObjectMapper;

/**
 * 실행기 테스트가 쓰는 합성 입력 폴더. 실제 노선이나 차량의 값이 아니다.
 *
 * <p>노선 204000057은 정류장 20개(순번 n의 정류장 ID는 2280000nn)를 지나고, 기준 시각 20초 전의 관측 묶음 하나에
 * 차량 다섯 대가 있다. 차량의 통과 순번은 bus-4가 14, bus-1이 13, bus-5가 12, bus-2가 9, bus-3이 1이다.
 * 첫 묶음에만 예보 실행 줄과 하차 반영 예측이 있다. 그 뒤 60초 간격의 묶음 셋(실행 줄 없음)에 같은 차량들의 뒤 관측이
 * 있다. 실제 결과 대조가 이 관측을 읽는다.
 * 노선 204000099는 public_route_id와 source_route_id가 다르다.
 */
final class SyntheticInput {
    static final Instant BASE = Instant.parse("2026-11-10T00:00:00Z");
    static final String ROUTE = "204000057";
    static final String ROUTE_WITH_OTHER_SOURCE_ID = "204000099";
    private static final String OTHER_SOURCE_ID = "224000099";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path folder;
    private final List<String> snapshots = new ArrayList<>();

    SyntheticInput(Path folder) throws IOException {
        this.folder = folder;
        write("route", "id,public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name",
            "1," + ROUTE + ",GBIS," + ROUTE + ",3330,기점,종점",
            "2," + ROUTE_WITH_OTHER_SOURCE_ID + ",GBIS," + OTHER_SOURCE_ID + ",9999,기점,종점");
        write("route_version",
            "id,route_id,content_digest,valid_from,up_first_departure_time,up_last_departure_time",
            "10,1," + "a".repeat(64) + ",2026-01-01T00:00:00Z,05:00,23:00",
            "20,2," + "b".repeat(64) + ",2026-01-01T00:00:00Z,05:00,23:00");
        List<String> stops = IntStream.rangeClosed(1, 20)
            .mapToObj(order -> "10," + order + "," + stationOf(order) + ",정류장 " + order + ",UP,t")
            .collect(Collectors.toCollection(ArrayList::new));
        stops.add("20,2,229000002,다른 노선 정류장,UP,t");
        write("route_stop", "route_version_id,stop_order,stop_id,name,direction,boarding_allowed",
            stops.toArray(String[]::new));
        write("model_deployment", "id,deployment_key,release_id,model_key,model_version,bundle_digest,"
                + "prediction_target_version,calculation_version,supported_scope_digest,data_until,state,activated_at",
            "1," + UUID.randomUUID() + ",synthetic-release,seat-forecast,synthetic-v1," + "c".repeat(64)
                + ",target-v1,calculation-v1," + "d".repeat(64) + ",2026-11-01T00:00:00Z,ACTIVE,2026-11-01T00:00:00Z");
        write("observation_batch", "id,route_version_id,scheduled_at,attempt_number,attempt_key,outcome,"
                + "normalization_version,collection_strategy_version,response_received_at,stored_rows",
            batch(100, -20), batch(101, 40), batch(102, 100), batch(103, 160));
        write("vehicle_observation", "id,observation_batch_id,route_version_id,source_row_number,vehicle_id,"
                + "stop_order,stop_id,running_state,remaining_seats,passed_stop_order",
            observation(1001, 100, 1, "bus-1", 13, 20), observation(1002, 100, 2, "bus-2", 9, 20),
            observation(1003, 100, 3, "bus-3", 1, 20), observation(1004, 100, 4, "bus-4", 14, 20),
            observation(1005, 100, 5, "bus-5", 12, 20),
            // 판정 시각 뒤의 관측. bus-4는 정류장 15를 떠난 첫 관측이 0석이고 마지막 관측이 2석이다.
            observation(1011, 101, 1, "bus-1", 14, 5), observation(1012, 101, 2, "bus-2", 10, 3),
            observation(1013, 101, 3, "bus-3", 1, 20), observation(1014, 101, 4, "bus-4", 15, 0),
            observation(1015, 101, 5, "bus-5", 13, 20),
            observation(1021, 102, 1, "bus-1", 15, 0), observation(1022, 102, 2, "bus-2", 10, 3),
            observation(1023, 102, 3, "bus-3", 1, 20), observation(1024, 102, 4, "bus-4", 15, 2),
            observation(1025, 102, 5, "bus-5", 13, 20),
            observation(1031, 103, 1, "bus-1", 16, 0), observation(1032, 103, 2, "bus-2", 11, 3),
            observation(1033, 103, 3, "bus-3", 1, 20), observation(1034, 103, 4, "bus-4", 16, 2),
            observation(1035, 103, 5, "bus-5", 14, 20));
        // 예보 착수 시각은 기준 시각 15초 전이고 예측은 다섯 개다. bus-2는 목표 정류장 둘(10, 15)을 한 줄에 담는다.
        write("forecast_run", "source_date,observation_batch_id,route_id,route_version_id,received_at,confirmed_at,"
                + "model_deployment_id,outcome,prediction_count",
            "2026-11-10,100,1,10," + BASE.minusSeconds(20) + "," + BASE.minusSeconds(15) + ",1,PUBLISHED,5");
        write("forecast_record", "source_date,vehicle_observation_id,observation_batch_id,source_row_number,route_id,"
                + "route_version_id,vehicle_id,passed_stop_order,trip_start_observation_id,chain_start_observation_id,"
                + "down,capacity,extended,target_stop_orders,expected_seats,full_probabilities",
            record(1004, 4, "bus-4", 14, "{15}", "{NULL}", "{0.3}"),
            record(1001, 1, "bus-1", 13, "{15}", "{NULL}", "{0.5}"),
            record(1005, 5, "bus-5", 12, "{15}", "{NULL}", "{0.4}"),
            record(1002, 2, "bus-2", 9, "{10,15}", "{NULL,NULL}", "{0.9,0.2}"));
    }

    /** 정류장 순번이 곧 정류장인 노선 204000057의 도착 스냅샷을 더한다. */
    SyntheticInput snapshot(int stopSequence, int receivedAfterSeconds, Prediction... predictions) {
        return snapshot(stationOf(stopSequence), receivedAfterSeconds,
            new RouteArrival(ROUTE, "3330", stopSequence, null, null, null, null, List.of(predictions)));
    }

    SyntheticInput snapshotWithoutRoutes(int stopSequence, int receivedAfterSeconds) {
        return snapshot(stationOf(stopSequence), receivedAfterSeconds);
    }

    SyntheticInput snapshotOfOtherRoute(int receivedAfterSeconds, Prediction... predictions) {
        return snapshot("229000002", receivedAfterSeconds,
            new RouteArrival(OTHER_SOURCE_ID, "9999", 2, null, null, null, null, List.of(predictions)));
    }

    private SyntheticInput snapshot(String stationId, int receivedAfterSeconds, RouteArrival... routes) {
        Instant received = BASE.plusSeconds(receivedAfterSeconds);
        ArrivalSnapshot snapshot = new ArrivalSnapshot(UUID.randomUUID(), stationId, received,
            received.plusSeconds(40), received.plusSeconds(60), null, List.of(routes));
        snapshots.add(String.join(",", snapshot.id().toString(), stationId, received.toString(),
            snapshot.refreshAfterAt().toString(), snapshot.staleAt().toString(),
            quoted(JSON.writeValueAsString(snapshot))));
        return this;
    }

    SyntheticInput decisions(String... rows) throws IOException {
        write(InputFolder.DECISION_FILE, "route_id,station_id,stop_sequence,decision_at", rows);
        return this;
    }

    /** 판정 구간의 예보 실행 줄이 입력에 하나도 없는 경우를 만든다. */
    SyntheticInput withoutPublications() throws IOException {
        Files.delete(folder.resolve("forecast_run.csv"));
        Files.delete(folder.resolve("forecast_record.csv"));
        return this;
    }

    /** 예보 기록으로 바뀌기 전의 형식으로 뽑은 예보 파일을 폴더에 더한다. */
    SyntheticInput withPreRecordForecastFile(String table) throws IOException {
        write(table, "id", "1");
        return this;
    }

    SyntheticInput firstPublication(long routeVersionId, Instant publishedAt) throws IOException {
        write("route_version_first_publication", "route_version_id,first_published_at",
            routeVersionId + "," + publishedAt);
        return this;
    }

    Path folder() throws IOException {
        write("arrival_snapshot", "id,station_id,received_at,refresh_after_at,stale_at,payload",
            snapshots.toArray(String[]::new));
        return folder;
    }

    static Prediction arrival(String vehicleId, int ordinal, int remainingSeconds) {
        return new Prediction(vehicleId, ordinal, remainingSeconds, null, null, null, "NOT_REPORTED", null, null,
            null);
    }

    static String stationOf(int stopSequence) {
        return "2280000%02d".formatted(stopSequence);
    }

    private static String batch(long id, int receivedAfterSeconds) {
        return id + ",10," + BASE.plusSeconds(receivedAfterSeconds - 2) + ",1,synthetic-" + id
            + ",SUCCESS_ROWS,normalization-v1,strategy-v1," + BASE.plusSeconds(receivedAfterSeconds) + ",5";
    }

    private static String observation(long id, long batchId, int row, String vehicleId, int passedStopOrder,
                                      int remainingSeats) {
        return id + "," + batchId + ",10," + row + "," + vehicleId + "," + passedStopOrder + ","
            + stationOf(passedStopOrder) + ",2," + remainingSeats + "," + passedStopOrder;
    }

    private static String record(
        final long observationId,
        final int row,
        String vehicleId,
        final int passedStopOrder,
        String targets,
        String expectedSeats,
        String fullProbabilities
    ) {
        return String.join(",", "2026-11-10", String.valueOf(observationId), "100", String.valueOf(row), "1", "10",
            vehicleId, String.valueOf(passedStopOrder), String.valueOf(observationId), String.valueOf(observationId),
            "f", "45", "f", quoted(targets), quoted(expectedSeats), quoted(fullProbabilities));
    }

    private static String quoted(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private void write(String table, String header, String... rows) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add(header);
        lines.addAll(List.of(rows));
        Files.write(folder.resolve(table + ".csv"), lines);
    }
}
