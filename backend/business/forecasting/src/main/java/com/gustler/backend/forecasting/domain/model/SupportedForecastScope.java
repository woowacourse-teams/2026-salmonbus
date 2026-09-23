package com.gustler.backend.forecasting.domain.model;

import com.gustler.backend.forecasting.domain.model.ForecastDistance;
import java.util.List;

/**
 * 모델 계수가 지원하는 예보 범위.
 *
 * <p>지원 노선 목록을 보관하며 예보 거리의 허용 범위는 {@link ForecastDistance}에서 검사한다.
 * 거리 판정 규칙을 중복 구현하지 않는다.
 *
 * <p>digest는 {@code model_deployment.supported_scope_digest}에 기록되며,
 * 해당 노선 버전을 지원하는 배포인지 확인할 때 사용한다.
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
