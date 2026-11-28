package com.gustler.backend.worker.configuration;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties("collection")
public record CollectionProperties(
    boolean enabled,
    List<String> routeIds,
    Duration fixedInterval,
    Set<String> forecastRequiredRouteIds,
    boolean allowPreforecastObservations
) {
    @ConstructorBinding
    public CollectionProperties {
        routeIds = routeIdsOrEmpty(routeIds);
        forecastRequiredRouteIds = forecastRequiredRouteIds == null ? Set.of() : Set.copyOf(forecastRequiredRouteIds);
        if (fixedInterval != null && fixedInterval.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("collection.fixed-interval은 1초 이상이어야 합니다.");
        }
    }

    public CollectionProperties(final boolean enabled, List<String> routeIds) {
        this(enabled, routeIds, null);
    }

    public CollectionProperties(final boolean enabled, List<String> routeIds, Duration fixedInterval) {
        this(enabled, routeIds, fixedInterval, Set.of(), false);
    }

    private static List<String> routeIdsOrEmpty(List<String> routeIds) {
        if (routeIds == null) {
            return List.of();
        }
        return List.copyOf(routeIds);
    }
}
