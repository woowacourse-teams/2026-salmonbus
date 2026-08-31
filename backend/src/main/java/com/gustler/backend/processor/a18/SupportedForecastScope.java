package com.gustler.backend.processor.a18;

import com.gustler.backend.processor.ForecastDistance;
import java.util.List;

/**
 * 이 계수 묶음이 예보할 수 있는 범위.
 *
 * <p>노선 목록은 묶음이 들고, 몇 정류장 앞까지인지는 {@link ForecastDistance} 가 이미 안다.
 * 거리 판정을 여기에 한 번 더 적으면 한쪽만 고쳐질 자리가 생긴다.
 *
 * <p>요약값을 낸다. {@code model_deployment.supported_scope_digest} 에 그대로 들어가고,
 * 조회가 이 노선 판본을 담는 배포인지 판정하는 데 쓴다.
 */
public record SupportedForecastScope(
    List<String> modelRoutes
) {

    public SupportedForecastScope {
        if (modelRoutes == null || modelRoutes.isEmpty()) {
            throw new IllegalArgumentException("예보할 노선이 하나는 있어야 한다");
        }
        modelRoutes = List.copyOf(modelRoutes);
    }

    public boolean covers(
        String modelRoute,
        final int stopsAhead
    ) {
        return modelRoutes.contains(modelRoute) && ForecastDistance.covers(stopsAhead);
    }

    /** 노선 순서까지 담는다. 순서가 곧 계수 배열의 자리라 뒤집히면 다른 범위다. */
    public String digest() {
        return Sha256.of(String.join(",", modelRoutes));
    }
}
