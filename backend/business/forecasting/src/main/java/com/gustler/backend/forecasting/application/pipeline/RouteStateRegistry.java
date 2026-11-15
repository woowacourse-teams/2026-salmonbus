package com.gustler.backend.forecasting.application.pipeline;

import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 노선 이름별 메모리 상태 등록부다. 상태는 그 상태를 만든 통계 객체와 함께 들고, 통계 객체가 바뀌면(모델 교체) 없는 것으로 본다.
 *
 * <p>상태를 읽고 바꾸는 것은 예보 단계 스레드 하나뿐이다. 다른 스레드는 {@link #invalidate}로 버릴 노선을 표시만 하고,
 * 예보 단계 스레드가 다음에 그 노선을 읽을 때 실제로 버린다. 그래서 처리 중인 노선을 버려도 처리 끝에 쓴 상태가 살아남지 않는다.
 * 크기 지표는 어느 스레드에서 읽어도 된다.
 *
 * <p>되감기가 실패한 노선은 정한 시각까지 다시 되감지 않도록 표시해 둔다. 창 안에 불완전한 배치가 있으면 창이 그 배치를 지날 때까지
 * 되감기가 계속 실패하므로, 배치마다 몇 시간치 관측을 다시 읽지 않게 하려는 것이다.
 *
 * <p>노선마다 이력에 들어간 배치 가운데 가장 늦은 수신 시각을 따로 기억한다. 상태를 버려도 남는다. 상태가 없어 예보하지 못한 배치도
 * 실행 줄이 남으면 다음 되감기의 이력에 들어가므로, 그런 배치보다 먼저 수신한 배치가 뒤늦게 들어오는 것을 가리는 데 쓴다.
 */
public final class RouteStateRegistry {
    private final Map<String, Entry> states = new ConcurrentHashMap<>();
    private final Set<String> invalidated = ConcurrentHashMap.newKeySet();
    private final Map<String, Instant> replayDeferredUntil = new ConcurrentHashMap<>();
    private final Map<String, Instant> latestReceived = new ConcurrentHashMap<>();

    /**
     * 노선의 상태를 돌려준다. 버리라는 표시가 있거나 통계 객체가 다르면 상태를 버리고 빈 값을 돌려준다.
     *
     * @param statistics 지금 처리에 쓰는 통계. 상태의 노선은 이 통계의 노선 이름이다
     * @return 이어 쓸 수 있는 상태
     */
    public Optional<RouteForecastState> find(AlightingAwareRouteStatistics statistics) {
        String route = statistics.route();
        if (invalidated.remove(route)) {
            states.remove(route);
            return Optional.empty();
        }
        Entry entry = states.get(route);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.statistics() != statistics) {
            states.remove(route);
            return Optional.empty();
        }
        return Optional.of(entry.state());
    }

    /**
     * 노선의 상태를 바꾼다. 저장을 커밋한 뒤에만 부른다.
     *
     * @param statistics 상태를 만든 통계
     * @param state 다음 배치에 넘길 상태
     */
    public void put(AlightingAwareRouteStatistics statistics, RouteForecastState state) {
        if (!statistics.route().equals(state.route())) {
            throw new IllegalArgumentException("통계와 상태의 노선이 다르다");
        }
        states.put(statistics.route(), new Entry(statistics, state));
    }

    /** 예보 단계 스레드에서 노선의 상태를 바로 버린다. */
    public void remove(String route) {
        states.remove(route);
    }

    /** 노선의 상태를 버리라고 표시한다. 어느 스레드에서 불러도 된다. */
    public void invalidate(String route) {
        invalidated.add(route);
    }

    /** 노선을 {@code until} 전까지 되감지 않도록 표시한다. */
    public void deferReplay(String route, Instant until) {
        replayDeferredUntil.put(route, until);
    }

    /** {@code at} 시각에 노선의 되감기를 미뤄야 하는지다. 미룬 시각이 지났으면 표시를 지운다. */
    public boolean replayDeferred(String route, Instant at) {
        Instant until = replayDeferredUntil.get(route);
        if (until == null) {
            return false;
        }
        if (at.isBefore(until)) {
            return true;
        }
        replayDeferredUntil.remove(route, until);
        return false;
    }

    /** 노선의 이력에 수신 시각이 {@code receivedAt}인 배치가 들어갔다고 기억한다. 예보 단계 스레드에서 저장을 커밋한 뒤에 부른다. */
    public void applied(String route, Instant receivedAt) {
        latestReceived.merge(route, receivedAt, RouteStateRegistry::later);
    }

    /** 수신 시각이 {@code receivedAt}인 배치가 이 노선의 이력에 들어간 배치보다 먼저 수신한 것인지다. */
    public boolean receivedBeforeApplied(String route, Instant receivedAt) {
        Instant latest = latestReceived.get(route);
        return latest != null && receivedAt.isBefore(latest);
    }

    private static Instant later(Instant kept, Instant added) {
        if (added.isAfter(kept)) {
            return added;
        }
        return kept;
    }

    /** 들고 있는 노선 상태 수. */
    public int routes() {
        return states.size();
    }

    /** 들고 있는 상태의 보존 관측 수 합. 메모리 크기의 근사다. */
    public long observations() {
        return states.values().stream().mapToLong(entry -> entry.state().observations().size()).sum();
    }

    /** 들고 있는 상태의 보존 통과 사건 수 합. 메모리 크기의 근사다. */
    public long events() {
        return states.values().stream().mapToLong(entry -> entry.state().events().size()).sum();
    }

    private record Entry(AlightingAwareRouteStatistics statistics, RouteForecastState state) {
    }
}
