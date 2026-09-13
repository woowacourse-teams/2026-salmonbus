package com.gustler.backend.migration.archive;

import com.gustler.backend.migration.MigrationException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

public record NormalizedObservation(
    int sourceRowNumber,
    String vehicleId,
    int stopOrder,
    String stopId,
    int passedStopOrder,
    int runningState,
    Integer remainingSeats,
    String seatUnknownReason,
    Integer crowdLevel,
    Integer vehicleType,
    Integer routeType,
    Integer tagless
) {

    private static final Set<String> FIELDS = Set.of(
        "crowd_level", "passed_stop_order", "remaining_seats", "route_type", "running_state",
        "seat_unknown_reason", "source_row_number", "stop_id", "stop_order", "tagless",
        "vehicle_id", "vehicle_type");

    public NormalizedObservation {
        if (sourceRowNumber < 0 || stopOrder < 1 || passedStopOrder < 0) {
            throw new MigrationException("ARCHIVE_OBSERVATION_ORDER_INVALID");
        }
        if (stopId == null || stopId.isBlank() || stopId.length() > 20) {
            throw new MigrationException("ARCHIVE_OBSERVATION_STOP_INVALID");
        }
        if (vehicleId == null || vehicleId.isBlank() || vehicleId.length() > 40) {
            throw new MigrationException("ARCHIVE_OBSERVATION_VEHICLE_ID_INVALID");
        }
        if (runningState < 0 || runningState > 2) {
            throw new MigrationException("ARCHIVE_OBSERVATION_RUNNING_STATE_INVALID");
        }
        boolean known = remainingSeats != null;
        if (known == (seatUnknownReason != null)
            || (known && remainingSeats < 0)
            || (!known && !Set.of("REPORTED_UNKNOWN", "NOT_REPORTED").contains(seatUnknownReason))) {
            throw new MigrationException("ARCHIVE_OBSERVATION_SEATS_INVALID");
        }
        if (crowdLevel != null && (crowdLevel < 1 || crowdLevel > 4)) {
            throw new MigrationException("ARCHIVE_OBSERVATION_CROWD_INVALID");
        }
    }

    Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("source_row_number", sourceRowNumber);
        value.put("vehicle_id", vehicleId);
        value.put("stop_order", stopOrder);
        value.put("stop_id", stopId);
        value.put("passed_stop_order", passedStopOrder);
        value.put("running_state", runningState);
        value.put("remaining_seats", remainingSeats);
        value.put("seat_unknown_reason", seatUnknownReason);
        value.put("crowd_level", crowdLevel);
        value.put("vehicle_type", vehicleType);
        value.put("route_type", routeType);
        value.put("tagless", tagless);
        return value;
    }

    static NormalizedObservation from(
        JsonNode node
    ) {
        ArchiveJson.requireObjectWithFields(node, FIELDS, "ARCHIVE_OBSERVATION_FIELDS_INVALID");
        return new NormalizedObservation(
            ArchiveJson.integer(node, "source_row_number"),
            ArchiveJson.nullableText(node, "vehicle_id"),
            ArchiveJson.integer(node, "stop_order"),
            ArchiveJson.text(node, "stop_id"),
            ArchiveJson.integer(node, "passed_stop_order"),
            ArchiveJson.integer(node, "running_state"),
            ArchiveJson.nullableInteger(node, "remaining_seats"),
            ArchiveJson.nullableText(node, "seat_unknown_reason"),
            ArchiveJson.nullableInteger(node, "crowd_level"),
            ArchiveJson.nullableInteger(node, "vehicle_type"),
            ArchiveJson.nullableInteger(node, "route_type"),
            ArchiveJson.nullableInteger(node, "tagless"));
    }

    @Override
    public String toString() {
        return "NormalizedObservation[sourceRowNumber=" + sourceRowNumber + ", vehicleId=***]";
    }
}
