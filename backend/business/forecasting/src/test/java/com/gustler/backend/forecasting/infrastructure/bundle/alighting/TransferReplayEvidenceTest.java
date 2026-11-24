package com.gustler.backend.forecasting.infrastructure.bundle.alighting;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.forecasting.domain.model.Sha256;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingTimeCell;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class TransferReplayEvidenceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CODE = "1".repeat(40);
    private static final String DIGEST = "2".repeat(64);
    private static final String ROUTE = "1007";

    @Test
    void 같은_노선의_실측과_시각만_옮긴_검증을_구분한다() {
        assertThatCode(() -> verify(cells())).doesNotThrowAnyException();
    }

    @Test
    void 원본의_차량_궤적이나_재생_파일이_다르면_거부한다() {
        for (String name : new String[]{"trajectory_sha256", "source_fixture_sha256"}) {
            ObjectNode cells = cells();
            trace(cells, "holiday-night").put(name, "f".repeat(64));
            assertThatThrownBy(() -> verify(cells)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void 실측_출퇴근_검증이_없으면_거부한다() {
        ObjectNode cells = cells();
        shifted(trace(cells, "work-evening"));
        assertThatThrownBy(() -> verify(cells)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 시간대_누락이나_정산_복원_실패를_거부한다() {
        ObjectNode missing = cells();
        missing.remove("holiday-day");
        assertThatThrownBy(() -> verify(missing)).isInstanceOf(IllegalArgumentException.class);
        ObjectNode notRestored = cells();
        ((ObjectNode) notRestored.path("work-night").path("routes").get(0)).put("restored_targets", 0);
        assertThatThrownBy(() -> verify(notRestored)).isInstanceOf(IllegalArgumentException.class);
    }

    private static void verify(ObjectNode cells) {
        TransferReplayEvidence.verifyRoute(
                ROUTE,
                cells,
                DIGEST,
                CODE
        );
    }

    private static ObjectNode trace(ObjectNode cells, String name) {
        return (ObjectNode) cells.path(name).path("routes").get(0).path("trace");
    }

    private static ObjectNode cells() {
        ObjectNode cells = JSON.createObjectNode();
        for (AlightingTimeCell cell : AlightingTimeCell.values()) {
            ObjectNode report = cells.putObject(cell.key());
            report.put("passed", true).put("bundle_manifest_sha256", DIGEST).put("verification_code_sha", CODE);
            report.put("runtime_capacity_policy", "vehicle-capacity-snapshot-v1");
            report.put("runtime_label_version", "alighting-confirmed-capacity-snapshot-v3");
            report.put("runtime_calculation_version", "alighting-online-capacity-snapshot-v2");
            report.put("settlement_scope", "after_alighting_only");
            ObjectNode row = report.putArray("routes").addObject();
            row.put("route", ROUTE).put("passed", true).put("cases", 2).put("settled_targets", 2);
            row.put("restored_targets", 1).put("max_feature_difference", 0).put("max_prediction_difference", 0);
            row.put("max_label_difference", 0).putArray("failures");
            row.putArray("extended_hours").addObject().put("route", ROUTE).put("cell", cell.key())
                .put("published", 2).put("settled", 2);
            ObjectNode trace = row.putObject("trace");
            trace.put("trajectory_sha256", "a".repeat(64)).put("fixture_sha256", Sha256.of(cell.key()));
            trace.put("kind", "field").put("first_response_at", Instant.EPOCH.toString());
            if (cell != AlightingTimeCell.WORK_EVENING && cell != AlightingTimeCell.WORK_DAY) {
                shifted(trace);
            }
        }
        return cells;
    }

    private static void shifted(ObjectNode trace) {
        trace.put("kind", "clock_replay").put("source_cell", "work-day")
            .put("source_fixture_sha256", Sha256.of("work-day"))
            .put("first_response_at", Instant.EPOCH.plusSeconds(3600).toString()).put("offset_millis", 3600000);
    }
}
