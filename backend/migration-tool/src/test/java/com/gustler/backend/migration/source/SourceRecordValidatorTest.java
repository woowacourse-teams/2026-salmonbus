package com.gustler.backend.migration.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.migration.CanonicalJson;
import com.gustler.backend.migration.MigrationException;
import com.gustler.backend.migration.Sha256;
import com.gustler.backend.migration.archive.ArchiveRecord;
import com.gustler.backend.migration.archive.RouteRoster;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class SourceRecordValidatorTest {

    private static final String VEHICLE = "synthetic-vehicle-never-source";
    private static final String PLATE = "synthetic-plate-never-source";
    private static final String VEHICLE_HMAC = "hmac-sha256:" + "a".repeat(64);
    private static final String PLATE_HMAC = "hmac-sha256:" + "b".repeat(64);
    private static final String RECORD_KEY =
        "records/route=3330/dt=2099-01-01/hh=00/20981231T150000.000Z_12345678_3330.record.json";
    private static final String RAW_KEY =
        "raw/route=3330/dt=2099-01-01/hh=00/20981231T150000.000Z_12345678_3330.json";

    private final ObjectMapper json = new ObjectMapper();

    @org.junit.jupiter.api.Test
    void verifiesRawBijectionAndProjectsOnlyCurrentNormalizedFields() throws Exception {
        Pair pair = pair();
        SourceRecordValidator.Validated validated = validator().validate(
            pair.record(), pair.raw(), Instant.parse("2099-01-02T00:00:00Z"));

        ArchiveRecord archive = validated.record();
        assertThat(archive.batch().attemptKey()).startsWith("s3v1:");
        assertThat(archive.observations()).singleElement().satisfies(observation -> {
            assertThat(observation.vehicleId()).isEqualTo(VEHICLE);
            assertThat(observation.passedStopOrder()).isZero();
            assertThat(observation.remainingSeats()).isNull();
            assertThat(observation.seatUnknownReason()).isEqualTo("REPORTED_UNKNOWN");
            assertThat(observation.crowdLevel()).isNull();
        });
        String privateArchive = new String(archive.canonicalBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(privateArchive)
            .contains(VEHICLE)
            .doesNotContain(PLATE)
            .doesNotContain(VEHICLE_HMAC)
            .doesNotContain(PLATE_HMAC)
            .doesNotContain("response_envelope")
            .doesNotContain("raw_response")
            .doesNotContain("serviceKey");
        assertThat(archive.toString()).doesNotContain(VEHICLE);
        assertThat(validated.toString()).doesNotContain(VEHICLE).doesNotContain(VEHICLE_HMAC);
    }

    @org.junit.jupiter.api.Test
    void rejectsChangedRawBodyWithoutEchoingPrivateValues() throws Exception {
        Pair pair = pair();
        byte[] changed = (new String(pair.raw().body(), java.nio.charset.StandardCharsets.UTF_8) + " ")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        SourceObjectStore.LoadedObject raw = new SourceObjectStore.LoadedObject(
            pair.raw().info(), changed, pair.raw().metadata(), false, null);

        assertThatThrownBy(() -> validator().validate(
            pair.record(), raw, Instant.parse("2099-01-02T00:00:00Z")))
            .isInstanceOf(MigrationException.class)
            .hasMessage("SOURCE_RAW_BODY_DIGEST_MISMATCH")
            .hasMessageNotContaining(VEHICLE)
            .hasMessageNotContaining(PLATE);
    }

    @org.junit.jupiter.api.Test
    void rejectsMissingPrivateVehicleIdentity() throws Exception {
        Pair pair = pair(null);

        assertThatThrownBy(() -> validator().validate(
            pair.record(), pair.raw(), Instant.parse("2099-01-02T00:00:00Z")))
            .isInstanceOf(MigrationException.class)
            .hasMessage("SOURCE_VEHICLE_ID_INVALID");
    }

    private Pair pair() throws Exception {
        return pair(VEHICLE);
    }

    private Pair pair(String vehicleId) throws Exception {
        Map<String, Object> rawRow = busRow(false, vehicleId);
        Map<String, Object> rawDocument = Map.of("response", Map.of(
            "msgHeader", Map.of("resultCode", 0, "resultMessage", "OK", "queryTime", ""),
            "msgBody", Map.of("busLocationList", List.of(rawRow))));
        byte[] rawBody = CanonicalJson.bytesOf(rawDocument);
        String rawSha = Sha256.of(rawBody);
        JsonNode envelope = json.readTree(rawBody);

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("schema_version", "1.0.0");
        record.put("record_id", "00000000-0000-4000-8000-000000000099");
        record.put("route", Map.of("name", "3330", "route_id", "204000057"));
        record.put("request", Map.of("parameters", Map.of("routeId", "204000057")));
        record.put("timing", Map.of(
            "request_started_at", timePair("2098-12-31T15:00:00Z", "2099-01-01T00:00:00+09:00"),
            "response_received_at", timePair("2098-12-31T15:00:00.200Z", "2099-01-01T00:00:00.200+09:00")));
        record.put("buses", List.of(busRow(true, vehicleId)));
        record.put("classification", Map.of(
            "type", "SUCCESS_WITH_VEHICLES", "vehicle_count", 1));
        record.put("http", Map.of(
            "status", 200, "response_sha256", rawSha, "response_bytes", rawBody.length));
        record.put("raw_response", Map.of(
            "s3_key", RAW_KEY, "sha256", rawSha, "exact_http_body_preserved", true));
        record.put("response_envelope", envelope);
        record.put("collection", Map.of(
            "strategy_version", "adaptive-kst-v1.2.0",
            "period_minutes", 1,
            "rounds_in_invocation", 1,
            "round_index", 0,
            "scheduled_at", "2098-12-31T14:59:59Z"));
        byte[] recordBody = CanonicalJson.bytesOf(record);
        SourceObjectStore.ObjectInfo recordInfo = new SourceObjectStore.ObjectInfo(
            SourceObjectStore.Kind.RECORD, RECORD_KEY, "record-etag", recordBody.length,
            Instant.parse("2098-12-31T15:00:01Z"));
        SourceObjectStore.ObjectInfo rawInfo = new SourceObjectStore.ObjectInfo(
            SourceObjectStore.Kind.RAW, RAW_KEY, "raw-etag", rawBody.length,
            Instant.parse("2098-12-31T15:00:01Z"));
        return new Pair(
            new SourceObjectStore.LoadedObject(recordInfo, recordBody, Map.of(), false, null),
            new SourceObjectStore.LoadedObject(rawInfo, rawBody, Map.of("sha256", rawSha), false, null));
    }

    private static Map<String, Object> busRow(boolean pseudonyms, String vehicleId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("vehId", vehicleId);
        row.put("plateNo", PLATE);
        row.put("routeId", "204000057");
        row.put("routeTypeCd", 1);
        row.put("stationId", "900000001");
        row.put("stationSeq", 1);
        row.put("remainSeatCnt", -1);
        row.put("crowded", 0);
        row.put("lowPlate", 1);
        row.put("taglessCd", null);
        row.put("stateCd", 1);
        if (pseudonyms) {
            row.put("pseudonyms", Map.of(
                "vehId_hmac", VEHICLE_HMAC,
                "plateNo_hmac", PLATE_HMAC));
        }
        return row;
    }

    private static Map<String, String> timePair(String utc, String kst) {
        return Map.of("utc", utc, "kst", kst);
    }

    private static SourceRecordValidator validator() {
        RouteRoster roster = new RouteRoster(
            "fixture-reference", LocalDate.parse("2099-01-01"), null, "3330", "204000057", 1,
            Map.of(1, new RouteRoster.Station(1, "900000001", true)), null);
        return new SourceRecordValidator(List.of(roster));
    }

    private record Pair(
        SourceObjectStore.LoadedObject record,
        SourceObjectStore.LoadedObject raw
    ) {
    }
}
