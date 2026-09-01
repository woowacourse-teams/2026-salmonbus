package com.gustler.backend.collector;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 무엇을 얼마나 돌 것인가.
 *
 * <p>노선을 늘리는 것은 여기 한 줄인데 하루 호출이 노선당 4,278회씩 는다.
 * 세 노선이면 12,834회로 한도 10,000회를 넘는다. 늘릴 때는 주기표도 같이 봐야 한다.
 * 넘치면 기동 때 경고가 뜬다.
 */
@ConfigurationProperties("collection")
public record CollectionProperties(
    boolean enabled,
    List<String> routeIds
) {

    public CollectionProperties {
        routeIds = routeIds == null ? List.of() : List.copyOf(routeIds);
    }
}
