package com.gustler.backend.forecasting.infrastructure.bundle.alighting;

import com.gustler.backend.forecasting.domain.deployment.ModelRelease;
import com.gustler.backend.forecasting.domain.deployment.SupportedForecastScope;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareSeatForecastModel;
import com.gustler.backend.forecasting.domain.model.alighting.ExtendedAlightingPredictor;
import com.gustler.backend.forecasting.domain.model.alighting.NumericTreeEnsemble;
import com.gustler.backend.forecasting.infrastructure.alighting.AlightingAwareStatisticsReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 검증 결과 파일을 확인하고 수치 대조를 통과한 모델 번들만 활성화 단계에 전달한다. */
public final class AlightingAwareServingBundle {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private AlightingAwareServingBundle() {
    }

    public static ModelRelease load(Path directory) throws IOException {
        return load(directory, false);
    }

    /**
     * @param allowSyntheticFixture {@link AlightingAwareInferenceBundle#loadForServing(Path, boolean)}와 같은 뜻이다
     */
    public static ModelRelease load(Path directory, final boolean allowSyntheticFixture) throws IOException {
        AlightingAwareInferenceBundle bundle =
            AlightingAwareInferenceBundle.loadForServing(directory, allowSyntheticFixture);
        JsonNode runtime = bundle.manifest().get("runtime");
        return ModelRelease.alighting(
                runtime.get("release_id").asString(),
                bundle.digest(),
                runtime.get("data_through").asString(),
                new SupportedForecastScope(bundle.routes()),
                readModel(bundle)
        );
    }

    /** 해시 검증을 마친 모델을 읽는다. 배포 생성이나 활성화는 수행하지 않는다. */
    public static AlightingAwareSeatForecastModel readModel(AlightingAwareInferenceBundle bundle) {
        return readModel(bundle, bundle.digest());
    }

    static AlightingAwareSeatForecastModel readModel(AlightingAwareInferenceBundle bundle, String digest) {
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
            Map<Integer, String> targets = targetEvidence(
                    route,
                    digest,
                    bundle,
                    stats,
                    raw,
                    settlement
            );
            evidence.put(route, targets);
        }
        return new AlightingAwareSeatForecastModel(
                bundle.predictor(),
                statistics,
                evidence,
                digest,
                extendedPredictors
        );
    }

    private static Map<Integer, String> targetEvidence(
        String route,
        String digest,
        AlightingAwareInferenceBundle bundle,
        AlightingAwareRouteStatistics stats,
        JsonNode raw,
        JsonNode settlement
    ) {
        Map<Integer, String> targets = new HashMap<>();
        for (AlightingAwareRouteStatistics.Stop stop : stats.stops()) {
            ObjectNode snapshot = JSON.createObjectNode();
            snapshot.put("route", route);
            snapshot.put("bundleDigest", digest);
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
