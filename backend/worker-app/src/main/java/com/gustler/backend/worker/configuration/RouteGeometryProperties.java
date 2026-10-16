package com.gustler.backend.worker.configuration;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties("route-geometry")
public record RouteGeometryProperties(boolean enabled, List<String> routeIds, boolean includeCatalog) {
    @ConstructorBinding
    public RouteGeometryProperties {
        routeIds = routeIds == null ? List.of() : List.copyOf(routeIds);
        if (routeIds.stream().anyMatch(id -> !id.matches("[0-9]{9}"))) {
            throw new IllegalArgumentException("형상 수집 노선은 9자리 ID여야 한다");
        }
    }

    public RouteGeometryProperties(boolean enabled, List<String> routeIds) {
        this(enabled, routeIds, false);
    }
}
