package com.gustler.backend.worker.configuration;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * 수집할 노선과 실행 주기를 설정한다.
 *
 * <p>설정에 노선 한 줄을 추가하면 하루 호출이 노선당 4,278회씩 는다.
 * 세 노선이면 12,834회로 한도 10,000회를 넘는다. 노선을 늘릴 때는 주기표도 함께 확인해야 한다.
 * 한도를 넘으면 기동 때 경고가 뜬다.
 */
@ConfigurationProperties("collection")
public record CollectionProperties(
    boolean enabled,
    List<String> routeIds,
    Duration fixedInterval
) {
    @ConstructorBinding
    public CollectionProperties {
        routeIds = routeIdsOrEmpty(routeIds);
        if (fixedInterval != null && fixedInterval.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("collection.fixed-interval은 1초 이상이어야 합니다.");
        }
    }

    public CollectionProperties(final boolean enabled, List<String> routeIds) {
        this(enabled, routeIds, null);
    }

    private static List<String> routeIdsOrEmpty(List<String> routeIds) {
        if (routeIds == null) {
            return List.of();
        }
        return List.copyOf(routeIds);
    }
}
