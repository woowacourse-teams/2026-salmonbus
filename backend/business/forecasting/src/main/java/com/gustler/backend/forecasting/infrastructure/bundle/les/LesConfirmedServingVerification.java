package com.gustler.backend.forecasting.infrastructure.bundle.les;

import com.gustler.backend.forecasting.domain.model.les.LesRouteStatistics;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** 새 입력 판본의 운영 범위는 같은 원본 번들로 통과한 실제 검증 범위에 한정한다. */
final class LesConfirmedServingVerification {
    private static final String PROFILE = LesRouteStatistics.CONFIRMED_PROFILE;
    private static final String CAPACITY = "observed-vehicle-prefix-max-through-closure-v2";

    private LesConfirmedServingVerification() {
    }

    static boolean isConfirmed(Map<String, JsonNode> files) {
        return PROFILE.equals(files.get("feature-contract.json").path("input_profile").asText());
    }

    static void verifyContract(JsonNode runtime, Map<String, JsonNode> files, List<String> routes) {
        verifyProfile(runtime);
        verifyProfile(files.get("feature-contract.json"));
        for (String route : routes) {
            require(PROFILE.equals(files.get("statistics-" + route + ".json").path("input_profile").asText()),
                "통계 입력 판본이 다르다: " + route);
        }
    }

    static void verify(
        String check,
        JsonNode receipt,
        JsonNode evidence,
        String sourceDigest,
        List<String> routes
    ) {
        verifyProfile(receipt);
        require(evidence.path("schema").asText().equals("les-confirmed-serving-evidence-v1"), "검증 증거 판본이 다르다");
        require(receipt.path("routes").equals(evidence.path("routes")), "검증 증거 노선이 다르다");
        require(names(receipt.path("routes")).equals(routes), "검증 노선이 운영 노선과 다르다");
        require(evidence.path("verified_cases").equals(receipt.path("verified_cases")), "검증 사례 수가 다르다");
        JsonNode result = evidence.path("result");
        require(result.path("passed").asBoolean(), "원본 검증 결과가 실패했다");
        require(result.path("bundle_manifest_sha256").asText().equals(sourceDigest), "검증 결과의 모델이 다르다");
        final int cases = verifiedCases(check, result, routes);
        require(receipt.path("verified_cases").asInt() == cases, "검증 사례 수가 원본 결과와 다르다");
    }

    private static int verifiedCases(String check, JsonNode result, List<String> routes) {
        return switch (check) {
        case "feature_parity" -> verifyNumeric(result);
        case "runtime_validation" -> verifyRuntime(result, routes);
        case "historical_replay" -> verifyWorker(result, routes);
        case "settlement" -> verifyWorker(result, routes) * 2;
        default -> throw new IllegalArgumentException("알 수 없는 운영 검증이다: " + check);
        };
    }

    private static int verifyRuntime(JsonNode result, List<String> routes) {
        require(names(result.path("routes")).equals(routes), "런타임 검증 노선이 다르다");
        require(result.path("input_profile").asText().equals(PROFILE), "런타임 입력 판본이 다르다");
        require(result.path("mode").asText().equals("candidate"), "후보 런타임 검증이 아니다");
        require(result.path("verified_cases").asInt() == routes.size(), "런타임 원본 사례 수가 다르다");
        return routes.size();
    }

    private static void verifyProfile(JsonNode value) {
        require(value.path("input_profile").asText().equals(PROFILE), "확인 시각 입력 판본이 다르다");
        require(value.path("label_version").asText().equals(LesRouteStatistics.CONFIRMED_LABEL), "정산 판본이 다르다");
        require(value.path("capacity_policy").asText().equals(CAPACITY), "정원 기준이 다르다");
    }

    private static int verifyNumeric(JsonNode result) {
        int cases = 0;
        for (String file : List.of("golden.json", "boundaries.json")) {
            JsonNode comparison = result.path(file);
            require(comparison.path("rows").asInt() > 0, "수치 대조 입력이 없다");
            cases += comparison.path("rows").asInt();
            JsonNode differences = comparison.path("max_absolute_difference");
            require(differences.isArray() && differences.size() == 6, "수치 대조 출력이 부족하다");
            for (JsonNode difference : differences) {
                require(withinTolerance(difference, 1e-9), "수치 대조 오차가 크다");
            }
        }
        return cases;
    }

    private static int verifyWorker(JsonNode result, List<String> routes) {
        JsonNode results = result.path("routes");
        require(results.isArray() && results.size() == routes.size(), "Worker 검증 노선 수가 다르다");
        int cases = 0;
        for (int index = 0; index < routes.size(); index++) {
            JsonNode row = results.get(index);
            verifyWorkerRoute(row, routes.get(index));
            cases += row.path("cases").asInt();
        }
        return cases;
    }

    private static void verifyWorkerRoute(JsonNode row, String route) {
        require(row.path("route").asText().equals(route), "Worker 검증 노선이 다르다");
        require(row.path("passed").asBoolean() && row.path("cases").asInt() > 0, "Worker 검증이 실패했다");
        require(row.path("settled_targets").asInt() == row.path("cases").asInt() * 2, "정산 검증이 누락됐다");
        require(row.path("failures").isArray() && row.path("failures").isEmpty(), "정산 근거가 다르다");
        require(withinTolerance(row.path("max_feature_difference"), 0), "Worker 입력이 다르다");
        require(withinTolerance(row.path("max_prediction_difference"), 1e-9), "Worker 예측이 다르다");
        require(withinTolerance(row.path("max_label_difference"), 1e-8), "Worker 정산이 다르다");
    }

    private static List<String> names(JsonNode values) {
        require(values.isArray(), "검증 노선 목록이 없다");
        return values.valueStream().map(JsonNode::asText).toList();
    }

    private static boolean withinTolerance(JsonNode value, final double tolerance) {
        if (!value.isNumber()) {
            return false;
        }
        final double difference = value.asDouble();
        return isNonnegativeFinite(difference) && difference <= tolerance;
    }

    private static boolean isNonnegativeFinite(final double value) {
        return Double.isFinite(value) && value >= 0;
    }

    private static void require(final boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
