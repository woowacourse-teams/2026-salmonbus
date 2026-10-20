package com.gustler.backend.forecasting.infrastructure.bundle;

import com.gustler.backend.forecasting.domain.model.ForecastRouteReference;
import com.gustler.backend.forecasting.domain.model.ForecastRouteReference.Stop;
import com.gustler.backend.forecasting.domain.model.ModelRoute;
import com.gustler.backend.forecasting.domain.model.RouteDirection;
import com.gustler.backend.forecasting.domain.model.Sha256;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** v2는 실제 정류장 기준 파일의 지문과 내용을 검사한 뒤 모델에 묶는다. */
final class BundleRouteReference {
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    private BundleRouteReference() {
    }

    static ForecastRouteReference read(byte[] bytes, BundleManifest manifest) {
        BundleCheck.ROUTE_REFERENCE.require(Sha256.of(bytes).equals(manifest.routeReferenceDigest()), "정류장 파일 지문 불일치");
        try {
            JsonNode root = JSON.readTree(BundleManifestReader.textOf(bytes));
            fields(root, Set.of("version", "routes"));
            BundleCheck.ROUTE_REFERENCE.require(text(root, "version").equals(manifest.routeReferenceVersion()),
            "정류장 기준 버전 불일치");
            JsonNode routes = root.get("routes");
            BundleCheck.ROUTE_REFERENCE.require(routes.isArray() && routes.size() == manifest.routes()
                .size(), "노선 수 불일치");
            Map<String, List<Stop>> result = new LinkedHashMap<>();
            for (int index = 0; index < routes.size(); index++) {
                JsonNode route = routes.get(index);
                fields(route, Set.of("modelRoute", "sourceRouteId", "stops"));
                String modelRoute = text(route, "modelRoute");
                BundleCheck.ROUTE_REFERENCE.require(modelRoute.equals(manifest.routes().get(index))
                    && ModelRoute.of(text(route, "sourceRouteId")).equals(modelRoute), "노선 순서 또는 ID 불일치");
                JsonNode stops = route.get("stops");
                BundleCheck.ROUTE_REFERENCE.require(stops.isArray() && !stops.isEmpty() && stops.size() <= 2000,
                "정류장 목록 오류");
                List<Stop> parsed = new ArrayList<>();
                for (JsonNode stop : stops) {
                    fields(stop, Set.of("order", "id", "boardingAllowed", "direction"));
                    JsonNode order = stop.get("order");
                    BundleCheck.ROUTE_REFERENCE.require(order.isIntegralNumber() && order.canConvertToInt()
                        && stop.get("boardingAllowed").isBoolean(), "순번 또는 승차 가능 여부 자료형 오류");
                    parsed.add(new Stop(order.intValue(), text(stop, "id"),
                        stop.get("boardingAllowed").booleanValue(), RouteDirection.valueOf(text(stop, "direction"))));
                }
                result.put(modelRoute, parsed);
            }
            return new ForecastRouteReference(result);
        } catch (BundleRejectedException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new BundleRejectedException("[ROUTE_REFERENCE] 정류장 기준을 읽을 수 없다", error);
        }
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        BundleCheck.ROUTE_REFERENCE.require(value != null && value.isString() && !value.stringValue().isBlank(), key);
        return value.stringValue();
    }

    private static void fields(JsonNode node, Set<String> expected) {
        BundleCheck.ROUTE_REFERENCE.require(node != null && node.isObject(), "객체가 아니다");
        Set<String> actual = new LinkedHashSet<>(node.propertyNames());
        BundleCheck.ROUTE_REFERENCE.require(actual.equals(expected), "필드 누락 또는 모르는 필드: " + actual);
    }
}
