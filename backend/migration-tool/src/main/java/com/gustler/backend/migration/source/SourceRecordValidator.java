package com.gustler.backend.migration.source;

import com.gustler.backend.migration.CanonicalJson;
import com.gustler.backend.migration.MigrationException;
import com.gustler.backend.migration.Sha256;
import com.gustler.backend.migration.archive.ArchiveRecord;
import com.gustler.backend.migration.archive.NormalizedObservation;
import com.gustler.backend.migration.archive.RouteRoster;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** private collector record와 raw response 한 쌍을 검증한 뒤 current worker 모양으로만 투영한다. */
public final class SourceRecordValidator {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String COLLECTOR_SCHEMA_VERSION = "1.0.0";
    private static final String NORMALIZATION_VERSION = "normalization-v1.0.0-s3-backfill";
    private static final String SOURCE_ACCOUNT = "827325854159";
    private static final String EMPTY_BODY_SHA256 = Sha256.of(new byte[0]);
    private static final Pattern STRICT_HMAC = Pattern.compile("hmac-sha256:[0-9a-f]{64}");
    private static final Pattern SAFE_VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern RECORD_PATH = Pattern.compile(
        "^records/route=(?<route>1650|3330)/"
            + "dt=(?<date>\\d{4}-\\d{2}-\\d{2})/hh=(?<hour>\\d{2})/"
            + "(?<stamp>\\d{8}T\\d{6}\\.\\d{3}Z)_"
            + "(?<attempt>[A-Za-z0-9-]{8})_(?<suffix>1650|3330)\\.record\\.json$");
    private static final List<String> BUS_FIELDS = List.of(
        "vehId", "plateNo", "routeId", "routeTypeCd", "stationId", "stationSeq",
        "remainSeatCnt", "crowded", "lowPlate", "taglessCd", "stateCd");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<RouteRoster> rosters;

    public SourceRecordValidator(
        List<RouteRoster> rosters
    ) {
        this.rosters = List.copyOf(rosters);
    }

    public Validated validate(
        SourceObjectStore.LoadedObject recordObject,
        SourceObjectStore.LoadedObject rawObject,
        Instant cutoffAt
    ) {
        Matcher path = RECORD_PATH.matcher(recordObject.info().key());
        if (!path.matches()) {
            throw new MigrationException("SOURCE_RECORD_PARTITION_INVALID");
        }
        JsonNode record = CanonicalJson.parse(recordObject.body(), "SOURCE_RECORD_JSON_INVALID");
        requireObject(record, "SOURCE_RECORD_INVALID");
        if (!COLLECTOR_SCHEMA_VERSION.equals(text(record, "schema_version"))) {
            throw new MigrationException("SOURCE_RECORD_SCHEMA_VERSION_INVALID");
        }
        UUID recordId;
        try {
            recordId = UUID.fromString(text(record, "record_id"));
        } catch (IllegalArgumentException e) {
            throw new MigrationException("SOURCE_RECORD_IDENTIFIER_INVALID", e);
        }
        JsonNode route = object(record, "route");
        String modelRoute = text(route, "name");
        String sourceRouteId = scalarText(route.get("route_id"), "SOURCE_ROUTE_ID_INVALID");
        if (!modelRoute.equals(path.group("route")) || !modelRoute.equals(path.group("suffix"))) {
            throw new MigrationException("SOURCE_ROUTE_PARTITION_MISMATCH");
        }
        JsonNode request = object(record, "request");
        JsonNode parameters = object(request, "parameters");
        if (!sourceRouteId.equals(scalarText(parameters.get("routeId"), "SOURCE_REQUEST_ROUTE_INVALID"))) {
            throw new MigrationException("SOURCE_REQUEST_ROUTE_MISMATCH");
        }

        JsonNode timing = object(record, "timing");
        Instant requestedAt = timePair(timing, "request_started_at");
        Instant receivedAt = timePair(timing, "response_received_at");
        requirePartitionTimes(path, requestedAt, receivedAt, cutoffAt);
        RouteRoster roster = RouteRoster.forObservation(rosters, modelRoute, receivedAt);
        if (!sourceRouteId.equals(roster.sourceRouteId())) {
            throw new MigrationException("SOURCE_ROUTE_REFERENCE_ID_MISMATCH");
        }

        JsonNode recordRows = array(record, "buses");
        int reportedCount = integer(object(record, "classification"), "vehicle_count");
        if (reportedCount != recordRows.size()) {
            throw new MigrationException("SOURCE_RECORD_ROW_COUNT_MISMATCH");
        }
        JsonNode http = object(record, "http");
        JsonNode rawReference = object(record, "raw_response");
        RawPair rawPair = validateRawPair(record, recordRows, http, rawReference, rawObject, path, sourceRouteId);
        JsonNode collection = object(record, "collection");
        String strategy = text(collection, "strategy_version");
        if (!SAFE_VERSION.matcher(strategy).matches()) {
            throw new MigrationException("SOURCE_COLLECTION_VERSION_INVALID");
        }
        validateCollectionTiming(collection);
        int roundIndex = integer(collection, "round_index");
        Instant scheduledAt = scheduledAt(collection, requestedAt);

        List<NormalizedObservation> observations = new ArrayList<>();
        List<IdentityPair> identities = new ArrayList<>();
        Set<String> sourceVehicleIds = new HashSet<>();
        int excluded = 0;
        for (int sourceRow = 0; sourceRow < recordRows.size(); sourceRow++) {
            JsonNode row = recordRows.get(sourceRow);
            requireObject(row, "SOURCE_RECORD_ROW_INVALID");
            IdentityPair identity = validatePrivateIdentity(row);
            identities.add(identity);
            String vehicleId = identity.vehicleId();
            if (!sourceVehicleIds.add(vehicleId)) {
                throw new MigrationException("SOURCE_DUPLICATE_VEHICLE_IN_RESPONSE");
            }
            Integer stopOrder = nullableInteger(row.get("stationSeq"));
            String stopId = nullableScalarText(row.get("stationId"));
            Integer runningState = nullableInteger(row.get("stateCd"));
            if (stopOrder == null || stopId == null || runningState == null
                || stopOrder < 1 || runningState < 0 || runningState > 2) {
                excluded++;
                continue;
            }
            roster.requireStation(stopOrder, stopId);
            Integer seats = nullableInteger(row.get("remainSeatCnt"));
            String unknownReason = null;
            if (seats == null) {
                unknownReason = "NOT_REPORTED";
            } else if (seats < 0) {
                seats = null;
                unknownReason = "REPORTED_UNKNOWN";
            }
            Integer crowd = nullableInteger(row.get("crowded"));
            if (crowd != null && (crowd < 1 || crowd > 4)) {
                crowd = null;
            }
            observations.add(new NormalizedObservation(
                sourceRow,
                vehicleId,
                stopOrder,
                stopId,
                runningState == 1 ? stopOrder - 1 : stopOrder,
                runningState,
                seats,
                unknownReason,
                crowd,
                nullableInteger(row.get("lowPlate")),
                nullableInteger(row.get("routeTypeCd")),
                nullableInteger(row.get("taglessCd"))));
        }

        Outcome outcome = outcomeOf(object(record, "classification"), http, rawPair.responseDocument(), recordRows.size());
        if (!outcome.outcome().startsWith("SUCCESS_") && !observations.isEmpty()) {
            excluded = recordRows.size();
            observations = List.of();
        }
        String rawBodySha256 = rawObject == null ? EMPTY_BODY_SHA256 : Sha256.of(rawObject.body());
        String semanticDigest = SemanticBatchDigest.of(
            SOURCE_ACCOUNT, sourceRouteId, requestedAt, receivedAt, rawBodySha256, roundIndex);
        ArchiveRecord archiveRecord = new ArchiveRecord(
            new ArchiveRecord.Batch(
                SOURCE_ACCOUNT,
                COLLECTOR_SCHEMA_VERSION,
                recordId,
                semanticDigest,
                "s3v1:" + semanticDigest,
                modelRoute,
                sourceRouteId,
                scheduledAt,
                requestedAt,
                receivedAt,
                1,
                nullableInteger(http.get("status")),
                resultCode(rawPair.responseDocument()),
                outcome.outcome(),
                outcome.failureCode(),
                recordRows.size(),
                observations.size(),
                excluded,
                NORMALIZATION_VERSION,
                strategy,
                "S3_BACKFILL"),
            observations);
        return new Validated(archiveRecord, identities, rawPair.expectedRawKey());
    }

    private static RawPair validateRawPair(
        JsonNode record,
        JsonNode recordRows,
        JsonNode http,
        JsonNode rawReference,
        SourceObjectStore.LoadedObject rawObject,
        Matcher path,
        String sourceRouteId
    ) {
        JsonNode rawKeyNode = rawReference.get("s3_key");
        String expectedKey = expectedRawKey(path);
        if (rawKeyNode != null && rawKeyNode.isString()) {
            if (!expectedKey.equals(rawKeyNode.stringValue()) || rawObject == null
                || !expectedKey.equals(rawObject.info().key())) {
                throw new MigrationException("SOURCE_RAW_REFERENCE_MISMATCH");
            }
            String bodySha = Sha256.of(rawObject.body());
            if (!bodySha.equals(text(http, "response_sha256"))
                || !bodySha.equals(text(rawReference, "sha256"))
                || !bodySha.equals(rawObject.metadata().get("sha256"))) {
                throw new MigrationException("SOURCE_RAW_BODY_DIGEST_MISMATCH");
            }
            if (longValue(http, "response_bytes") != rawObject.body().length
                || !bool(rawReference, "exact_http_body_preserved")) {
                throw new MigrationException("SOURCE_RAW_PRESERVATION_MISMATCH");
            }
            JsonNode response = CanonicalJson.parse(rawObject.body(), "SOURCE_RAW_JSON_INVALID");
            if (!response.equals(record.get("response_envelope"))) {
                throw new MigrationException("SOURCE_RESPONSE_ENVELOPE_MISMATCH");
            }
            JsonNode rawRows = rawBusRows(response);
            requireRowsMatch(recordRows, rawRows, sourceRouteId);
            return new RawPair(response, expectedKey);
        }
        if (rawKeyNode == null || !rawKeyNode.isNull() || rawObject != null
            || longValue(http, "response_bytes") != 0
            || !EMPTY_BODY_SHA256.equals(text(http, "response_sha256"))
            || bool(rawReference, "exact_http_body_preserved")
            || (record.get("response_envelope") != null && !record.get("response_envelope").isNull())) {
            throw new MigrationException("SOURCE_EMPTY_RAW_RECORD_INVALID");
        }
        if (!recordRows.isEmpty()) {
            throw new MigrationException("SOURCE_EMPTY_RAW_ROWS_INVALID");
        }
        return new RawPair(null, null);
    }

    private static void requireRowsMatch(
        JsonNode recordRows,
        JsonNode rawRows,
        String sourceRouteId
    ) {
        if (recordRows.size() != rawRows.size()) {
            throw new MigrationException("SOURCE_RAW_ROW_COUNT_MISMATCH");
        }
        for (int index = 0; index < recordRows.size(); index++) {
            JsonNode recordRow = recordRows.get(index);
            JsonNode rawRow = rawRows.get(index);
            for (String field : BUS_FIELDS) {
                if (!sameScalar(recordRow.get(field), rawRow.get(field))) {
                    throw new MigrationException("SOURCE_NORMALIZED_ROW_MISMATCH");
                }
            }
            if (!sourceRouteId.equals(scalarText(recordRow.get("routeId"), "SOURCE_ROW_ROUTE_INVALID"))) {
                throw new MigrationException("SOURCE_ROW_ROUTE_MISMATCH");
            }
        }
    }

    private static JsonNode rawBusRows(
        JsonNode document
    ) {
        if (document == null || !document.isObject()) {
            return JSON.createArrayNode();
        }
        JsonNode response = document.get("response");
        JsonNode body = response == null ? null : response.get("msgBody");
        JsonNode rows = body == null ? null : body.get("busLocationList");
        if (rows == null || rows.isNull()) {
            return JSON.createArrayNode();
        }
        if (rows.isObject()) {
            tools.jackson.databind.node.ArrayNode wrapped = JSON.createArrayNode();
            wrapped.add(rows);
            return wrapped;
        }
        if (!rows.isArray()) {
            throw new MigrationException("SOURCE_RAW_ROWS_INVALID");
        }
        return rows;
    }

    private static IdentityPair validatePrivateIdentity(
        JsonNode row
    ) {
        String vehicleId = scalarText(row.get("vehId"), "SOURCE_VEHICLE_ID_INVALID");
        if (vehicleId.isBlank()) {
            throw new MigrationException("SOURCE_VEHICLE_ID_INVALID");
        }
        JsonNode pseudonyms = object(row, "pseudonyms");
        String vehicleHmac = text(pseudonyms, "vehId_hmac");
        String plateHmac = text(pseudonyms, "plateNo_hmac");
        if (!STRICT_HMAC.matcher(vehicleHmac).matches() || !STRICT_HMAC.matcher(plateHmac).matches()) {
            throw new MigrationException("SOURCE_HMAC_FORMAT_INVALID");
        }
        return new IdentityPair(vehicleId, vehicleHmac);
    }

    private static Outcome outcomeOf(
        JsonNode classification,
        JsonNode http,
        JsonNode response,
        int rows
    ) {
        String type = text(classification, "type");
        Integer status = nullableInteger(http.get("status"));
        JsonNode header = response == null ? null : response.path("response").path("msgHeader");
        String code = header == null || header.isMissingNode() ? null : nullableScalarText(header.get("resultCode"));
        String message = header == null || header.isMissingNode() ? null : nullableScalarText(header.get("resultMessage"));
        if ("SUCCESS_WITH_VEHICLES".equals(type)
            && status != null && status >= 200 && status < 300 && "0".equals(code)) {
            return new Outcome(rows > 0 ? "SUCCESS_ROWS" : "SUCCESS_EMPTY", null);
        }
        if ("SUCCESS_NO_VEHICLE".equals(type)
            && status != null && status >= 200 && status < 300 && "4".equals(code)
            && "결과가 존재하지 않습니다.".equals(message) && rows == 0) {
            return new Outcome("SUCCESS_EMPTY", null);
        }
        if ("TRANSPORT_ERROR".equals(type)) {
            return new Outcome("UNKNOWN_AFTER_DISPATCH", null);
        }
        if ("INCOMPLETE_ENVELOPE".equals(type)) {
            return new Outcome("FAILED_UNREADABLE", null);
        }
        if ("API_ERROR".equals(type) || "HTTP_ERROR".equals(type)) {
            return new Outcome("FAILED_UPSTREAM", quotaFailure(code));
        }
        throw new MigrationException("SOURCE_CLASSIFICATION_INVALID");
    }

    private static String quotaFailure(
        String resultCode
    ) {
        if ("22".equals(resultCode)) {
            return "DAILY_QUOTA_EXCEEDED";
        }
        if ("23".equals(resultCode)) {
            return "PER_SECOND_QUOTA_EXCEEDED";
        }
        return "UPSTREAM_ERROR";
    }

    private static Integer resultCode(
        JsonNode response
    ) {
        if (response == null) {
            return null;
        }
        JsonNode value = response.path("response").path("msgHeader").get("resultCode");
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            return Integer.valueOf(scalarText(value, "SOURCE_RESULT_CODE_INVALID"));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void validateCollectionTiming(
        JsonNode collection
    ) {
        BigDecimal period = decimal(collection.get("period_minutes"), "SOURCE_COLLECTION_PERIOD_INVALID");
        BigDecimal rounds = decimal(collection.get("rounds_in_invocation"), "SOURCE_COLLECTION_ROUNDS_INVALID");
        if (period.signum() <= 0 || rounds.signum() <= 0 || rounds.stripTrailingZeros().scale() > 0) {
            throw new MigrationException("SOURCE_COLLECTION_TIMING_INVALID");
        }
    }

    private static Instant scheduledAt(
        JsonNode collection,
        Instant fallback
    ) {
        JsonNode value = collection.get("scheduled_at");
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.isString()) {
            throw new MigrationException("SOURCE_SCHEDULED_AT_INVALID");
        }
        try {
            Instant scheduled = Instant.parse(value.stringValue());
            if (scheduled.isAfter(fallback)) {
                throw new MigrationException("SOURCE_SCHEDULED_AT_AFTER_REQUEST");
            }
            return scheduled;
        } catch (DateTimeParseException e) {
            throw new MigrationException("SOURCE_SCHEDULED_AT_INVALID", e);
        }
    }

    private static void requirePartitionTimes(
        Matcher path,
        Instant requestedAt,
        Instant receivedAt,
        Instant cutoffAt
    ) {
        if (receivedAt.isBefore(requestedAt) || receivedAt.isAfter(cutoffAt)) {
            throw new MigrationException("SOURCE_RESPONSE_TIME_OUTSIDE_CUTOFF");
        }
        OffsetDateTime requestedKst = requestedAt.atZone(KST).toOffsetDateTime();
        OffsetDateTime receivedKst = receivedAt.atZone(KST).toOffsetDateTime();
        boolean normal = requestedKst.toLocalDate().toString().equals(path.group("date"))
            && receivedKst.toLocalDate().equals(requestedKst.toLocalDate())
            && String.format("%02d", requestedKst.getHour()).equals(path.group("hour"));
        boolean repairedCrossing = requestedKst.toLocalDate().toString().equals(path.group("date"))
            && receivedKst.toLocalDate().equals(requestedKst.toLocalDate().plusDays(1))
            && String.format("%02d", requestedKst.getHour()).equals(path.group("hour"))
            && Duration.between(requestedAt, receivedAt).compareTo(Duration.ofSeconds(30)) <= 0;
        if (!normal && !repairedCrossing) {
            throw new MigrationException("SOURCE_DATE_PARTITION_MISMATCH");
        }
    }

    private static Instant timePair(
        JsonNode timing,
        String field
    ) {
        JsonNode pair = object(timing, field);
        try {
            OffsetDateTime utc = OffsetDateTime.parse(text(pair, "utc"));
            OffsetDateTime kst = OffsetDateTime.parse(text(pair, "kst"));
            if (!ZoneOffset.ofHours(9).equals(kst.getOffset())
                || Math.abs(Duration.between(utc.toInstant(), kst.toInstant()).toMillis()) > 1) {
                throw new MigrationException("SOURCE_TIME_PAIR_MISMATCH");
            }
            return utc.toInstant();
        } catch (DateTimeParseException e) {
            throw new MigrationException("SOURCE_TIME_PAIR_INVALID", e);
        }
    }

    private static String expectedRawKey(
        Matcher path
    ) {
        return "raw/route=" + path.group("route")
            + "/dt=" + path.group("date")
            + "/hh=" + path.group("hour")
            + "/" + path.group("stamp")
            + "_" + path.group("attempt")
            + "_" + path.group("suffix") + ".json";
    }

    public static String expectedRawKeyOf(
        String recordKey
    ) {
        Matcher path = RECORD_PATH.matcher(recordKey);
        if (!path.matches()) {
            throw new MigrationException("SOURCE_RECORD_PARTITION_INVALID");
        }
        return expectedRawKey(path);
    }

    private static boolean sameScalar(
        JsonNode first,
        JsonNode second
    ) {
        if ((first == null || first.isNull()) && (second == null || second.isNull())) {
            return true;
        }
        if (first == null || second == null
            || first.isObject() || first.isArray() || second.isObject() || second.isArray()) {
            return false;
        }
        if (first.isNumber() && second.isNumber()) {
            return first.decimalValue().compareTo(second.decimalValue()) == 0;
        }
        return first.equals(second);
    }

    private static JsonNode object(
        JsonNode node,
        String field
    ) {
        JsonNode value = node.get(field);
        if (value == null || !value.isObject()) {
            throw new MigrationException("SOURCE_OBJECT_FIELD_INVALID");
        }
        return value;
    }

    private static JsonNode array(
        JsonNode node,
        String field
    ) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            throw new MigrationException("SOURCE_ARRAY_FIELD_INVALID");
        }
        return value;
    }

    private static void requireObject(
        JsonNode node,
        String code
    ) {
        if (node == null || !node.isObject()) {
            throw new MigrationException(code);
        }
    }

    private static String text(
        JsonNode node,
        String field
    ) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.stringValue().isBlank()) {
            throw new MigrationException("SOURCE_TEXT_FIELD_INVALID");
        }
        return value.stringValue();
    }

    private static String scalarText(
        JsonNode value,
        String code
    ) {
        String text = nullableScalarText(value);
        if (text == null) {
            throw new MigrationException(code);
        }
        return text;
    }

    private static String nullableScalarText(
        JsonNode value
    ) {
        if (value == null || value.isNull() || value.isObject() || value.isArray() || value.isBoolean()) {
            return null;
        }
        return value.asString();
    }

    private static int integer(
        JsonNode node,
        String field
    ) {
        Integer value = nullableInteger(node.get(field));
        if (value == null) {
            throw new MigrationException("SOURCE_INTEGER_FIELD_INVALID");
        }
        return value;
    }

    private static Integer nullableInteger(
        JsonNode value
    ) {
        if (value == null || value.isNull() || value.isBoolean()) {
            return null;
        }
        if (value.isIntegralNumber() && value.canConvertToInt()) {
            return value.intValue();
        }
        if (value.isFloatingPointNumber()) {
            BigDecimal decimal = value.decimalValue();
            try {
                return decimal.intValueExact();
            } catch (ArithmeticException ignored) {
                return null;
            }
        }
        return null;
    }

    private static BigDecimal decimal(
        JsonNode value,
        String code
    ) {
        if (value == null || !value.isNumber()) {
            throw new MigrationException(code);
        }
        BigDecimal decimal = value.decimalValue();
        if (decimal == null) {
            throw new MigrationException(code);
        }
        return decimal;
    }

    private static long longValue(
        JsonNode node,
        String field
    ) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new MigrationException("SOURCE_LONG_FIELD_INVALID");
        }
        return value.longValue();
    }

    private static boolean bool(
        JsonNode node,
        String field
    ) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) {
            throw new MigrationException("SOURCE_BOOLEAN_FIELD_INVALID");
        }
        return value.booleanValue();
    }

    public record Validated(
        ArchiveRecord record,
        List<IdentityPair> identities,
        String referencedRawKey
    ) {

        public Validated {
            identities = List.copyOf(identities);
        }

        @Override
        public String toString() {
            return "Validated[record=***, identities=***]";
        }
    }

    public record IdentityPair(
        String vehicleId,
        String vehicleHmac
    ) {

        @Override
        public String toString() {
            return "IdentityPair[vehicleId=***, vehicleHmac=***]";
        }
    }

    private record RawPair(
        JsonNode responseDocument,
        String expectedRawKey
    ) {
    }

    private record Outcome(
        String outcome,
        String failureCode
    ) {
    }

}
