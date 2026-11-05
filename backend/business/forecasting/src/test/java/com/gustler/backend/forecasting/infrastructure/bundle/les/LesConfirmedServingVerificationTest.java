package com.gustler.backend.forecasting.infrastructure.bundle.les;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.forecasting.domain.model.les.LesRouteStatistics;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 합성 증거로 적재 경계를 검사한다. 실제 모델 PASS 증거로 사용하지 않는다. */
class LesConfirmedServingVerificationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> ROUTES = List.of("3330", "G8110");
    private static final String DIGEST = "b".repeat(64);

    @Test
    void 같은_원본의_전체_노선과_새_입력_판본을_검증한다() {
        assertThatCode(() -> {
            for (String check : List.of("feature_parity", "historical_replay", "settlement", "runtime_validation")) {
                ObjectNode receipt = receipt(check);
                LesConfirmedServingVerification.verify(check, receipt, evidence(check), DIGEST, ROUTES);
            }
        }).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"profile", "label", "capacity", "routes", "cases", "model", "failed",
        "missing_settlement", "evidence_ids", "feature_difference", "prediction_difference", "label_difference"})
    void 해시가_맞아도_다른_계약이나_불완전한_검증을_거부한다(String fault) {
        // given
        ObjectNode receipt = receipt("settlement");
        ObjectNode evidence = evidence("settlement");
        ObjectNode result = (ObjectNode) evidence.get("result");
        ObjectNode route = (ObjectNode) result.get("routes").get(1);
        switch (fault) {
        case "profile" -> receipt.put("input_profile", "les-original-v1");
        case "label" -> receipt.put("label_version", "les-identity-safe-v1");
        case "capacity" -> receipt.put("capacity_policy", "day-max");
        case "routes" -> receipt.set("routes", JSON.valueToTree(List.of("3330")));
        case "cases" -> receipt.put("verified_cases", 5);
        case "model" -> result.put("bundle_manifest_sha256", "c".repeat(64));
        case "failed" -> result.put("passed", false);
        case "missing_settlement" -> route.put("settled_targets", 1);
        case "evidence_ids" -> route.set("failures", JSON.valueToTree(List.of(Map.of("mismatch", "post_id"))));
        case "feature_difference" -> route.put("max_feature_difference", .01);
        case "prediction_difference" -> route.put("max_prediction_difference", .01);
        case "label_difference" -> route.put("max_label_difference", .01);
        default -> throw new IllegalArgumentException(fault);
        }

        // then
        assertThatThrownBy(() -> LesConfirmedServingVerification.verify(
            "settlement", receipt, evidence, DIGEST, ROUTES)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 원본_수치_대조_실패를_PASS_표시로_숨길_수_없다() {
        ObjectNode evidence = evidence("feature_parity");
        ObjectNode numeric = (ObjectNode) evidence.get("result").get("golden.json");
        numeric.set("max_absolute_difference", JSON.valueToTree(List.of(0, 0, 0, 0, .1, 0)));
        assertThatThrownBy(() -> LesConfirmedServingVerification.verify(
            "feature_parity", receipt("feature_parity"), evidence, DIGEST, ROUTES))
            .hasMessageContaining("오차");
    }

    @Test
    void 노선_하나라도_이전_입력_통계이면_적재를_거부한다() {
        ObjectNode contract = receipt("runtime_validation");
        ObjectNode oldStatistics = JSON.createObjectNode().put("input_profile", "les-original-v1");
        Map<String, JsonNode> files = Map.of(
            "feature-contract.json", contract, "statistics-3330.json", contract,
            "statistics-G8110.json", oldStatistics);
        assertThatThrownBy(() -> LesConfirmedServingVerification.verifyContract(contract, files, ROUTES))
            .hasMessageContaining("통계 입력 판본");
    }

    private ObjectNode receipt(String check) {
        ObjectNode receipt = JSON.createObjectNode();
        receipt.put("input_profile", LesRouteStatistics.CONFIRMED_PROFILE);
        receipt.put("label_version", LesRouteStatistics.CONFIRMED_LABEL);
        receipt.put("capacity_policy", "observed-vehicle-prefix-max-through-closure-v2");
        receipt.set("routes", JSON.valueToTree(ROUTES));
        receipt.put("verified_cases", 2);
        if (check.equals("settlement")) {
            receipt.put("verified_cases", 4);
        }
        return receipt;
    }

    private ObjectNode evidence(String check) {
        ObjectNode result = JSON.createObjectNode();
        result.put("passed", true);
        result.put("bundle_manifest_sha256", DIGEST);
        result.set("routes", JSON.valueToTree(ROUTES.stream().map(route -> Map.of(
            "route", route, "passed", true, "cases", 1, "settled_targets", 2, "failures", List.of(),
            "max_feature_difference", 0, "max_prediction_difference", 0, "max_label_difference", 0)).toList()));
        if (check.equals("feature_parity")) {
            Map<String, Object> numeric = Map.of("rows", 1, "max_absolute_difference", List.of(0, 0, 0, 0, 0, 0));
            result.set("golden.json", JSON.valueToTree(numeric));
            result.set("boundaries.json", JSON.valueToTree(numeric));
        }
        if (check.equals("runtime_validation")) {
            result.set("routes", JSON.valueToTree(ROUTES));
            result.put("input_profile", LesRouteStatistics.CONFIRMED_PROFILE);
            result.put("mode", "candidate");
            result.put("verified_cases", ROUTES.size());
        }
        ObjectNode evidence = JSON.createObjectNode();
        evidence.put("schema", "les-confirmed-serving-evidence-v1");
        evidence.set("routes", JSON.valueToTree(ROUTES));
        evidence.set("verified_cases", receipt(check).get("verified_cases"));
        evidence.set("result", result);
        return evidence;
    }
}
