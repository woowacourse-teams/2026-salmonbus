package com.gustler.backend.worker.configuration;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * 지도 형상 수집의 활성 여부·공개 노선 범위·카탈로그 포함 여부를 바인딩한다.
 */
@ConfigurationProperties("route-geometry")
public record RouteGeometryProperties(boolean enabled, List<String> routeIds, boolean includeCatalog) {
    @ConstructorBinding
    public RouteGeometryProperties {
        routeIds = routeIdsOrEmpty(routeIds);
        if (routeIds.stream().anyMatch(id -> !id.matches("[0-9]{9}"))) {
            throw new IllegalArgumentException("형상 수집 노선은 9자리 ID여야 한다");
        }
    }

    public RouteGeometryProperties(final boolean enabled, List<String> routeIds) {
        this(enabled, routeIds, false);
    }

    private static List<String> routeIdsOrEmpty(List<String> routeIds) {
        if (routeIds == null) {
            return List.of();
        }
        return List.copyOf(routeIds);
    }
}
