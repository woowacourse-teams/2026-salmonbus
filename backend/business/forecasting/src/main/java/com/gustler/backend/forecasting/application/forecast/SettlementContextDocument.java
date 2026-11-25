package com.gustler.backend.forecasting.application.forecast;

import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareSeatForecastModel;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 모델 배포에 노선 버전마다 한 번 적는 정산 근거({@code model_deployment.settlement_context -> 노선 버전})의 JSON을 만든다.
 *
 * <p>근거는 모델과 노선 통계만으로 정해지며, 정산({@code JdbcSettlementContextProvider})이 이 문서를 읽어 계수 해시를 만든다. 키
 * 순서와 숫자 표기를 바꾸면 해시가 달라지므로 만드는 순서를 바꾸지 않는다.
 */
public final class SettlementContextDocument {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private SettlementContextDocument() {
    }

    /**
     * 모델이 노선의 정산 근거를 갖고 있는지 반환한다.
     *
     * @param model 모델
     * @param statistics 노선 통계
     * @return 정류장 하나 이상의 근거가 있으면 참
     */
    public static boolean available(AlightingAwareSeatForecastModel model, AlightingAwareRouteStatistics statistics) {
        Map<Integer, String> targets = model.settlementEvidence(statistics.route());
        return targets != null && !targets.isEmpty();
    }

    /**
     * 정산 근거 JSON을 만든다.
     *
     * @param model 모델
     * @param statistics 노선 통계
     * @return {@code {"shared": ..., "stops": ...}} 문서
     * @throws IllegalStateException 모델에 이 노선의 근거가 없으면 던진다
     */
    public static String of(AlightingAwareSeatForecastModel model, AlightingAwareRouteStatistics statistics) {
        Map<Integer, String> targets = model.settlementEvidence(statistics.route());
        if (targets == null || targets.isEmpty()) {
            throw new IllegalStateException("노선의 정산 근거가 없다: " + statistics.route());
        }
        ObjectNode shared = sharedContext(JSON.readTree(targets.values().iterator().next()), statistics);
        ObjectNode stops = stopsContext(targets);
        ObjectNode context = JSON.createObjectNode();
        context.set("shared", shared);
        context.set("stops", stops);
        return JSON.writeValueAsString(context);
    }

    private static ObjectNode sharedContext(JsonNode source, AlightingAwareRouteStatistics statistics) {
        ObjectNode shared = JSON.createObjectNode();
        shared.put("capacityPolicy", "vehicle-capacity-snapshot-v1");
        shared.put("inputProfile", statistics.profile().input());
        for (String field : List.of("turn", "realStopOrders", "extendedSettlement")) {
            if (source.has(field)) {
                shared.set(field, source.get(field));
            }
        }
        if (source.has("holidays")) {
            List<String> holidays = source.path("holidays").valueStream()
                .map(JsonNode::asString)
                .sorted()
                .toList();
            shared.set("holidays", JSON.valueToTree(holidays));
        }
        return shared;
    }

    private static ObjectNode stopsContext(Map<Integer, String> targets) {
        ObjectNode stops = JSON.createObjectNode();
        targets.forEach((order, evidence) -> {
            JsonNode parameters = JSON.readTree(evidence).path("alighting").path("stops");
            stops.set(order.toString(), stopParameters(parameters));
        });
        return stops;
    }

    private static JsonNode stopParameters(JsonNode parameters) {
        if (parameters.isArray()) {
            return parameters;
        }
        return JSON.createArrayNode();
    }
}
