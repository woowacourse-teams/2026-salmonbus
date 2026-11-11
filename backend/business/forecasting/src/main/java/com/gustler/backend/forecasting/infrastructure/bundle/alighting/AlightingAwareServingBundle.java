package com.gustler.backend.forecasting.infrastructure.bundle.alighting;

import com.gustler.backend.forecasting.domain.deployment.ModelRelease;
import com.gustler.backend.forecasting.domain.deployment.SupportedForecastScope;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareSeatForecastModel;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import com.gustler.backend.forecasting.infrastructure.alighting.AlightingAwareStatisticsReader;
import com.gustler.backend.forecasting.domain.model.alighting.ExtendedAlightingPredictor;
import com.gustler.backend.forecasting.domain.model.alighting.NumericTreeEnsemble;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 검증 영수증과 실제 대조 계산을 통과한 묶음만 기존 활성화 경계에 넘긴다. */
public final class AlightingAwareServingBundle {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private AlightingAwareServingBundle() {
    }

    public static ModelRelease load(Path directory) throws IOException {
        AlightingAwareInferenceBundle bundle = AlightingAwareInferenceBundle.loadForServing(directory);
        JsonNode runtime = bundle.manifest().get("runtime");
        return ModelRelease.alighting(runtime.get("release_id").asString(), bundle.digest(),
            runtime.get("data_through").asString(), new SupportedForecastScope(bundle.routes()), readModel(bundle));
    }

    /** 해시 검증을 마친 모델을 읽는다. 배포 생성이나 활성화는 수행하지 않는다. */
    public static AlightingAwareSeatForecastModel readModel(AlightingAwareInferenceBundle bundle) {
        Map<String, AlightingAwareRouteStatistics> statistics = new HashMap<>();
        Map<String, Map<Integer, String>> evidence = new HashMap<>();
        JsonNode extended = bundle.extendedHours();
        Map<String, ExtendedAlightingPredictor> extendedPredictors = new HashMap<>();
        NumericTreeEnsemble full = null;
        if (extended != null) {
            full = AlightingAwareInferenceBundle.model(extended.get("full"));
        }
        for (String route : bundle.routes()) {
            JsonNode raw = bundle.statistics(route);
            AlightingAwareRouteStatistics stats = AlightingAwareStatisticsReader.read(raw, bundle.holidays());
            statistics.put(route, stats);
            JsonNode settlement = null;
            if (extended != null) {
                JsonNode extension = extended.path("routes").path(route);
                extendedPredictors.put(route, new ExtendedAlightingPredictor(
                    AlightingAwareInferenceBundle.model(extension.get("seats")), full));
                settlement = extension.get("settlement");
            }
            Map<Integer, String> targets = targetEvidence(route, bundle, stats, raw, settlement);
            evidence.put(route, targets);
        }
        return new AlightingAwareSeatForecastModel(bundle.predictor(), statistics, evidence, bundle.digest(), extendedPredictors);
    }

    private static Map<Integer, String> targetEvidence(
        String route,
        AlightingAwareInferenceBundle bundle,
        AlightingAwareRouteStatistics stats,
        JsonNode raw,
        JsonNode settlement
    ) {
        Map<Integer, String> targets = new HashMap<>();
        for (AlightingAwareRouteStatistics.Stop stop : stats.stops()) {
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
            if (settlement != null) {
                snapshot.set("extendedSettlement", settlement);
            }
            targets.put(stop.order(), JSON.writeValueAsString(snapshot));
        }
        return targets;
    }

}
