package com.gustler.backend.forecasting.infrastructure.bundle.alighting;

import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import tools.jackson.databind.JsonNode;

/** 같은 모델과 노선으로 생성된 검증 요약·원본 결과가 운영 적재 조건을 충족하는지 확인한다. */
final class AlightingAwareConfirmedServingVerification {

    private static final String INPUT_PROFILE = AlightingAwareRouteStatistics.CONFIRMED_PROFILE;
    private static final String CAPACITY_POLICY = "observed-vehicle-prefix-max-through-closure-v2";
    private static final String EVIDENCE_SCHEMA = "alighting-confirmed-serving-evidence-v1";
    private static final String CANDIDATE_RUNTIME_MODE = "candidate";
    private static final String AFTER_ALIGHTING_SCOPE = "after_alighting_only";

    private static final List<String> NUMERIC_COMPARISON_FILES = List.of("golden.json", "boundaries.json");
    /** base·분기 확률 세 개·하차 후 좌석·출발 만석 확률을 원본 계산과 대조한다. */
    private static final int NUMERIC_OUTPUT_COUNT = 6;
    private static final double NUMERIC_COMPARISON_TOLERANCE = 1e-9;

    private static final double WORKER_FEATURE_TOLERANCE = 0;
    private static final double WORKER_PREDICTION_TOLERANCE = 1e-9;
    private static final double WORKER_LABEL_TOLERANCE = 1e-8;

    private static final int LEGACY_TARGETS_PER_FORECAST = 2;
    private static final int AFTER_ALIGHTING_TARGETS_PER_FORECAST = 1;

    private AlightingAwareConfirmedServingVerification() {
    }

    static boolean isConfirmed(Map<String, JsonNode> files) {
        JsonNode featureContract = files.get("feature-contract.json");
        String inputProfile = featureContract.path("input_profile").asText();
        return INPUT_PROFILE.equals(inputProfile);
    }

    static void verifyInputProfile(JsonNode document) {
        JsonNode profile = document.get("input_profile");
        if (profile == null || profile.isNull()) {
            return;
        }
        require(profile.isString(), "예보 입력 판본은 문자열이어야 한다");
        require(
            List.of(INPUT_PROFILE, "alighting-original-v1").contains(profile.asString()),
            "지원하지 않는 예보 입력 판본이다"
        );
    }

    static void verifyContract(JsonNode runtime, Map<String, JsonNode> files, List<String> routes) {
        verifyProfile(runtime);
        verifyProfile(files.get("feature-contract.json"));

        routes.forEach(route -> {
            JsonNode statistics = files.get("statistics-" + route + ".json");
            String statisticsProfile = statistics.path("input_profile").asText();
            require(INPUT_PROFILE.equals(statisticsProfile), "통계 입력 판본이 다르다: " + route);
        });
    }

    static void verify(
        String check,
        JsonNode receipt,
        JsonNode evidence,
        String sourceDigest,
        List<String> routes
    ) {
        verifyProfile(receipt);
        verifyEvidenceSummary(receipt, evidence, routes);

        JsonNode originalResult = evidence.path("result");
        require(originalResult.path("passed").asBoolean(), "원본 검증 결과가 실패했다");
        require(
            originalResult.path("bundle_manifest_sha256").asText().equals(sourceDigest),
            "검증 결과의 모델이 다르다"
        );

        final int verifiedCaseCount = verifiedCases(check, originalResult, routes);
        require(receipt.path("verified_cases").asInt() == verifiedCaseCount, "검증 사례 수가 원본 결과와 다르다");
    }

    private static void verifyEvidenceSummary(JsonNode receipt, JsonNode evidence, List<String> routes) {
        String evidenceSchema = evidence.path("schema").asText();
        require(evidenceSchema.equals(EVIDENCE_SCHEMA), "검증 증거 판본이 다르다");

        require(receipt.path("routes").equals(evidence.path("routes")), "검증 증거 노선이 다르다");
        require(routeNames(receipt.path("routes")).equals(routes), "검증 노선이 운영 노선과 다르다");

        require(evidence.path("verified_cases").equals(receipt.path("verified_cases")), "검증 사례 수가 다르다");
    }

    static int verifiedCases(String check, JsonNode result, List<String> routes) {
        return switch (check) {
            case "feature_parity" -> verifyNumeric(result);
            case "runtime_validation" -> verifyRuntime(result, routes);
            case "historical_replay", "settlement" -> verifyWorker(result, routes, check);
            default -> throw new IllegalArgumentException("알 수 없는 운영 검증이다: " + check);
        };
    }

    private static int verifyRuntime(JsonNode result, List<String> routes) {
        require(routeNames(result.path("routes")).equals(routes), "런타임 검증 노선이 다르다");

        String inputProfile = result.path("input_profile").asText();
        require(inputProfile.equals(INPUT_PROFILE), "런타임 입력 판본이 다르다");
        require(result.path("mode").asText().equals(CANDIDATE_RUNTIME_MODE), "후보 런타임 검증이 아니다");

        require(result.path("verified_cases").asInt() == routes.size(), "런타임 원본 사례 수가 다르다");
        return routes.size();
    }

    private static void verifyProfile(JsonNode contract) {
        String inputProfile = contract.path("input_profile").asText();
        require(inputProfile.equals(INPUT_PROFILE), "확인 시각 입력 판본이 다르다");

        String labelVersion = contract.path("label_version").asText();
        require(labelVersion.equals(AlightingAwareRouteStatistics.CONFIRMED_LABEL), "정산 판본이 다르다");

        require(contract.path("capacity_policy").asText().equals(CAPACITY_POLICY), "정원 기준이 다르다");
    }

    private static int verifyNumeric(JsonNode result) {
        return NUMERIC_COMPARISON_FILES.stream()
            .map(result::path)
            .mapToInt(AlightingAwareConfirmedServingVerification::verifyNumericComparison)
            .sum();
    }

    private static int verifyNumericComparison(JsonNode comparison) {
        final int rowCount = comparison.path("rows").asInt();
        require(rowCount > 0, "수치 대조 입력이 없다");

        JsonNode differences = comparison.path("max_absolute_difference");
        require(differences.isArray() && differences.size() == NUMERIC_OUTPUT_COUNT, "수치 대조 출력이 부족하다");
        require(
            differences.valueStream().allMatch(difference -> withinTolerance(difference, NUMERIC_COMPARISON_TOLERANCE)),
            "수치 대조 오차가 크다"
        );
        return rowCount;
    }

    private static int verifyWorker(JsonNode result, List<String> routes, String check) {
        final int targetsPerForecast = settlementTargetsPerForecast(result);
        JsonNode routeResults = result.path("routes");
        require(routeResults.isArray() && routeResults.size() == routes.size(), "Worker 검증 노선 수가 다르다");

        final int verifiedCaseCount = IntStream.range(0, routes.size())
            .map(index -> verifyWorkerRoute(routeResults.get(index), routes.get(index), targetsPerForecast))
            .sum();
        if ("settlement".equals(check)) {
            return verifiedCaseCount * targetsPerForecast;
        }
        return verifiedCaseCount;
    }

    /** 정산 범위를 기록하지 않은 과거 결과는 예보 하나당 대상 두 개를 검증한 형식이다. */
    private static int settlementTargetsPerForecast(JsonNode result) {
        JsonNode settlementScope = result.path("settlement_scope");
        if (settlementScope.isMissingNode()) {
            return LEGACY_TARGETS_PER_FORECAST;
        }
        require(AFTER_ALIGHTING_SCOPE.equals(settlementScope.asText()), "알 수 없는 정산 검증 범위다");
        return AFTER_ALIGHTING_TARGETS_PER_FORECAST;
    }

    private static int verifyWorkerRoute(JsonNode routeResult, String expectedRoute, final int targetsPerForecast) {
        require(routeResult.path("route").asText().equals(expectedRoute), "Worker 검증 노선이 다르다");
        require(routeResult.path("passed").asBoolean() && routeResult.path("cases").asInt() > 0, "Worker 검증이 실패했다");

        final int verifiedCaseCount = routeResult.path("cases").asInt();
        final int expectedSettledTargets = verifiedCaseCount * targetsPerForecast;
        require(routeResult.path("settled_targets").asInt() == expectedSettledTargets, "정산 검증 건수가 다르다");

        JsonNode settlementFailures = routeResult.path("failures");
        require(settlementFailures.isArray() && settlementFailures.isEmpty(), "정산 근거가 다르다");

        require(
            withinTolerance(routeResult.path("max_feature_difference"), WORKER_FEATURE_TOLERANCE),
            "Worker 입력이 다르다"
        );
        require(
            withinTolerance(routeResult.path("max_prediction_difference"), WORKER_PREDICTION_TOLERANCE),
            "Worker 예측이 다르다"
        );
        require(
            withinTolerance(routeResult.path("max_label_difference"), WORKER_LABEL_TOLERANCE),
            "Worker 정산이 다르다"
        );
        return verifiedCaseCount;
    }

    private static List<String> routeNames(JsonNode values) {
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
