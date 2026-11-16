package com.gustler.backend.forecasting.application.forecast;

import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 노선 이름별 메모리 상태 등록부다. 상태는 그 상태를 만든 통계 객체와 함께 들고, 통계 객체가 바뀌면(모델 교체) 없는 것으로 본다.
 *
 * <p>한 노선의 상태를 읽고 바꾸는 것은 그 노선을 처리하는 예보 단계 스레드 하나뿐이다. 크기 지표는 어느 스레드에서 읽어도 된다.
 *
 * <p>되감기가 실패한 노선은 정한 시각까지 다시 되감지 않도록 표시해 둔다. 창 안에 불완전한 배치가 있으면 창이 그 배치를 지날 때까지
 * 되감기가 계속 실패하므로, 배치마다 몇 시간치 관측을 다시 읽지 않게 하려는 것이다.
 */
public final class RouteStateRegistry {
    private final Map<String, Entry> states = new ConcurrentHashMap<>();
    private final Map<String, Instant> replayDeferredUntil = new ConcurrentHashMap<>();

    /**
     * 노선의 상태를 돌려준다. 통계 객체가 다르면 상태를 버리고 빈 값을 돌려준다.
     *
     * @param statistics 지금 처리에 쓰는 통계. 상태의 노선은 이 통계의 노선 이름이다
     * @return 이어 쓸 수 있는 상태
     */
    public Optional<RouteForecastState> find(AlightingAwareRouteStatistics statistics) {
        String route = statistics.route();
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
