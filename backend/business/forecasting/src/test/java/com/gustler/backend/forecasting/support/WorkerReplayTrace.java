package com.gustler.backend.forecasting.support;

import com.gustler.backend.forecasting.domain.model.Sha256;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

final class WorkerReplayTrace {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private WorkerReplayTrace() {
    }

    static JsonNode describe(JsonNode fixture, String fixtureDigest) {
        Instant start = Instant.parse(fixture.path("batches").get(0).path("response_at").asString());
        ObjectNode track = JSON.createObjectNode();
        for (String name : List.of("route", "source_route_id", "route_versions", "prefix")) {
            track.set(name, fixture.path(name));
        }
        track.set("stops", fixture.path("statistics").path("route_metadata"));
        track.set("batches", relative(fixture.path("batches"), start, List.of("confirmed_at", "response_at")));
        track.set("observations", relative(fixture.path("observations"), start, List.of("received_at", "physical_received_at")));
        ArrayNode cases = track.putArray("cases");
        for (JsonNode item : fixture.path("cases")) {
            ObjectNode ids = cases.addObject();
            for (String key : List.of("source_id", "target", "expected_pre", "expected_post", "expected_closure",
                "expected_previous_post", "expected_capacity")) {
                ids.set(key, item.path(key));
            }
        }
        ObjectNode trace = JSON.createObjectNode();
        trace.put("fixture_sha256", fixtureDigest);
        trace.put("trajectory_sha256", Sha256.of(JSON.writeValueAsBytes(track)));
        trace.put("first_response_at", start.toString());
        if (!fixture.has("clock_scenario")) {
            trace.put("kind", "field");
            return trace;
        }
        JsonNode shift = fixture.get("clock_scenario");
        trace.put("kind", "clock_replay");
        trace.put("source_cell", shift.path("source_cell").asString());
        trace.put("source_fixture_sha256", shift.path("source_fixture_sha256").asString());
        trace.put("offset_millis", Math.round(shift.path("offset_seconds").asDouble() * 1000));
        return trace;
    }

    private static ArrayNode relative(JsonNode rows, Instant start, List<String> times) {
        ArrayNode result = JSON.createArrayNode();
        for (JsonNode row : rows) {
            ObjectNode copy = JSON.createObjectNode();
            for (Map.Entry<String, JsonNode> item : row.properties()) {
                if (times.contains(item.getKey())) {
                    copy.put(item.getKey(), Duration.between(start, Instant.parse(item.getValue().asString())).toNanos());
                } else {
                    copy.set(item.getKey(), item.getValue());
                }
            }
            result.add(copy);
        }
        return result;
    }
}
