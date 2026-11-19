package com.gustler.backend.forecasting.infrastructure.bundle.alighting;

import static com.gustler.backend.forecasting.infrastructure.bundle.alighting.TransferredServingBundle.require;

import com.gustler.backend.forecasting.domain.model.alighting.AlightingTimeCell;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

final class TransferReplayEvidence {
    private TransferReplayEvidence() {
    }

    static void verify(JsonNode evidence, Set<String> additions, AlightingAwareInferenceBundle candidate, String releaseId) {
        String digest = candidate.digest();
        require("alighting-transfer-evidence-v1".equals(evidence.path("schema").asString(""))
            && digest.equals(evidence.path("candidate_sha256").asString("")), "전이 검증의 후보가 다르다");
        String code = evidence.path("verification_code_sha").asString("");
        require(code.matches("[0-9a-f]{40}"), "Worker 재생 코드 식별자가 없다");
        checkReport(evidence.path("numeric"), digest, "feature_parity", candidate.routes());
        JsonNode runtime = evidence.path("runtime");
        checkReport(runtime, digest, "runtime_validation", candidate.routes());
        require(releaseId.equals(runtime.path("release_id").asString(""))
            && "alighting-online-capacity-snapshot-v2".equals(runtime.path("runtime_calculation_version").asString("")),
            "후보 런타임 식별자가 다르다");
        JsonNode routes = evidence.path("routes");
        require(routes.isObject() && Set.copyOf(routes.propertyNames()).equals(additions), "신규 노선 전부의 검증이 필요하다");
        for (String route : additions) {
            verifyRoute(route, routes.path(route), digest, code);
        }
    }

    static void verifyRoute(String route, JsonNode cells, String digest, String code) {
        require(cells.isObject() && Set.copyOf(cells.propertyNames()).equals(Set.copyOf(AlightingTimeCell.keys())),
            "시간대별 발행·정산 검증 누락: " + route);
        Set<String> fixtures = new HashSet<>();
        boolean rushField = false;
        boolean extendedField = false;
        for (AlightingTimeCell cell : AlightingTimeCell.values()) {
            JsonNode report = cells.path(cell.key());
            checkReport(report, digest, "settlement", List.of(route));
            require(code.equals(report.path("verification_code_sha").asString(""))
                && "vehicle-capacity-snapshot-v1".equals(report.path("runtime_capacity_policy").asString(""))
                && "alighting-confirmed-capacity-snapshot-v3".equals(report.path("runtime_label_version").asString(""))
                && "alighting-online-capacity-snapshot-v2".equals(report.path("runtime_calculation_version").asString("")),
                "재생 코드 또는 정산 계약이 다르다");
            JsonNode row = report.path("routes").get(0);
            JsonNode periods = row.path("extended_hours");
            require(periods.isArray() && periods.size() == 1, "단일 시간대의 검증이 필요하다");
            JsonNode period = periods.get(0);
            require(route.equals(period.path("route").asString("")) && cell.key().equals(period.path("cell").asString(""))
                && period.path("published").asInt(-1) == row.path("cases").asInt()
                && period.path("settled").asInt(-1) == row.path("cases").asInt(), "시간대 발행·정산 수가 다르다");
            require(cell.isRush() || row.path("restored_targets").asInt() > 0, "시간 확장 예보 복원 누락");
            JsonNode trace = row.path("trace");
            String fixture = trace.path("fixture_sha256").asString("");
            require(fixture.matches("[0-9a-f]{64}") && fixtures.add(fixture)
                && trace.path("trajectory_sha256").asString("").matches("[0-9a-f]{64}"), "재생 입력 식별자 누락 또는 중복");
            if ("field".equals(trace.path("kind").asString(""))) {
                rushField |= cell.isRush();
                extendedField |= !cell.isRush();
            } else {
                verifyShift(trace, cells);
            }
        }
        require(rushField && extendedField, "출퇴근 모델과 시간 확장 모델 모두 같은 노선의 실측 재생이 필요하다");
    }

    private static void verifyShift(JsonNode trace, JsonNode cells) {
        require("clock_replay".equals(trace.path("kind").asString("")), "알 수 없는 재생 근거");
        JsonNode sourceRows = cells.path(trace.path("source_cell").asString("")).path("routes");
        require(sourceRows.isArray() && sourceRows.size() == 1, "원본 시간대 검증이 없다");
        JsonNode source = sourceRows.get(0).path("trace");
        require("field".equals(source.path("kind").asString(""))
            && source.path("fixture_sha256").equals(trace.path("source_fixture_sha256"))
            && source.path("trajectory_sha256").equals(trace.path("trajectory_sha256")),
            "원본 노선의 실측 운행 기록과 재생 기록이 다르다");
        long shift = Math.round(Duration.between(Instant.parse(source.path("first_response_at").asString("")),
            Instant.parse(trace.path("first_response_at").asString(""))).toNanos() / 1_000_000.0);
        require(shift != 0 && trace.path("offset_millis").isIntegralNumber()
            && shift == trace.path("offset_millis").asLong(), "재생 시각 이동량이 다르다");
    }

    private static void checkReport(JsonNode report, String digest, String check, List<String> routes) {
        require(report.path("passed").isBoolean() && report.path("passed").booleanValue()
            && digest.equals(report.path("bundle_manifest_sha256").asString("")), "후보에 대한 검증이 통과하지 않았다");
        AlightingAwareConfirmedServingVerification.verifiedCases(check, report, routes);
    }
}
