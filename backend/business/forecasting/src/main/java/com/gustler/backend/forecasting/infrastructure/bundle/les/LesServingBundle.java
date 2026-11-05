package com.gustler.backend.forecasting.infrastructure.bundle.les;

import com.gustler.backend.forecasting.domain.deployment.ModelRelease;
import com.gustler.backend.forecasting.domain.deployment.SupportedForecastScope;
import com.gustler.backend.forecasting.domain.model.les.LesForecastModel;
import com.gustler.backend.forecasting.domain.model.les.LesRouteStatistics;
import com.gustler.backend.forecasting.infrastructure.les.LesStatisticsReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 검증 영수증과 실제 대조 계산을 통과한 묶음만 기존 활성화 경계에 넘긴다. */
public final class LesServingBundle {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private LesServingBundle() {
    }

    public static ModelRelease load(Path directory) throws IOException {
        LesInferenceBundle bundle = LesInferenceBundle.loadForServing(directory);
        JsonNode runtime = bundle.manifest().get("runtime");
        return ModelRelease.les(runtime.get("release_id").asString(), bundle.digest(),
            runtime.get("data_through").asString(), new SupportedForecastScope(bundle.routes()), readModel(bundle));
    }

    /** 해시 검증을 마친 모델을 읽는다. 배포 생성이나 활성화는 수행하지 않는다. */
    public static LesForecastModel readModel(LesInferenceBundle bundle) {
        Map<String, LesRouteStatistics> statistics = new HashMap<>();
        Map<String, Map<Integer, String>> evidence = new HashMap<>();
        for (String route : bundle.routes()) {
            JsonNode raw = bundle.statistics(route);
            LesRouteStatistics stats = LesStatisticsReader.read(raw, bundle.holidays());
            statistics.put(route, stats);
            Map<Integer, String> targets = targetEvidence(route, bundle, stats, raw);
            evidence.put(route, targets);
        }
        return new LesForecastModel(bundle.predictor(), statistics, evidence, bundle.digest());
    }

    private static Map<Integer, String> targetEvidence(
        String route,
        LesInferenceBundle bundle,
        LesRouteStatistics stats,
        JsonNode raw) {
        Map<Integer, String> targets = new HashMap<>();
        for (LesRouteStatistics.Stop stop : stats.stops()) {
            ObjectNode snapshot = JSON.createObjectNode();
            snapshot.put("route", route);
            snapshot.put("bundleDigest", bundle.digest());
            snapshot.put("capacityPolicy", stats.profile().capacity());
            snapshot.put("inputProfile", stats.profile().input());
            snapshot.put("turn", stats.turn());
            snapshot.set("holidays", JSON.valueToTree(bundle.holidays().stream().map(Object::toString).toList()));
            snapshot.set("realStopOrders", JSON.valueToTree(stats.realStopOrders()));
            ObjectNode alighting = JSON.createObjectNode();
            alighting.put("route", route);
            ArrayNode parameters = JSON.createArrayNode();
            for (JsonNode row : raw.get("alighting").get("stops")) {
                if (row.get("stop").intValue() == stop.order()) {
                    parameters.add(row.deepCopy());
                }
            }
            alighting.set("stops", parameters);
            snapshot.set("alighting", alighting);
            targets.put(stop.order(), JSON.writeValueAsString(snapshot));
        }
        return targets;
    }

}
