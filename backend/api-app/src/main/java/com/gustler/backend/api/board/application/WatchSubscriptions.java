package com.gustler.backend.api.board.application;

import com.gustler.backend.api.route.RouteId;
import java.time.OffsetDateTime;
import java.util.Set;

/**
 * 판정보드 신호 스트림을 보고 있는 화면들에 닿는 경계다. 변경 감지기는 연결을 이 경계로만 안다.
 */
public interface WatchSubscriptions {
    /**
     * 지금 연결이 하나라도 있는 노선들이다. 연결이 없거나 서버가 내려가는 중이면 비어 있다.
     */
    Set<RouteId> watchedRoutes();

    /**
     * 이 노선에서 지금 읽히는 관측의 시각을 알린다.
     *
     * <p>그 노선의 연결들에 마지막으로 알린 값과 다를 때만 연결마다 신호를 하나씩 보낸다. 같은 값을 다시 알리면 아무 일도 없으므로
     * 확인할 때마다 불러도 된다. 다르다는 것은 새롭다는 뜻이 아니다. 고른 관측이 과거로 돌아가도 알린다. 아직 알린 적이 없는
     * 노선이면 처음 값을 알린다. 이 호출 뒤에 연결되는 화면은 연결 직후에 이 값을 받는다.
     *
     * <p>연결이 없는 노선이면 기억하지 않고 버린다. 마지막 연결이 끊긴 노선의 값도 함께 잊는다. 읽지 못한 노선은 부르지 않는다.
     * 그러면 알려 둔 값이 그대로 남아서, 같은 관측으로 다시 읽혔을 때 신호가 한 번 더 가지 않는다.
     *
     * <p>한 번에 한 스레드만 부른다. 두 스레드가 다른 값을 엇갈려 알리면 옛 값이 나중에 알려질 수 있다.
     */
    void observed(RouteId routeId, OffsetDateTime observedAt);
}
