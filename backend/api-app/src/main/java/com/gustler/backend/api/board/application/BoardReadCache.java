package com.gustler.backend.api.board.application;

import com.gustler.backend.api.board.domain.Board;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 노선마다 마지막으로 만든 판정보드를 둔다.
 *
 * <p>판정보드는 {@link BoardSnapshot}(노선 판본·관측 회차·발행·활성 모델·판본 표시)이 같으면 같은 값이다. 그래서 시간이 아니라
 * 스냅샷이 같은지로 재사용을 정한다. 호출하는 쪽은 요청마다 오래됨을 검사하고 관측 시간대의 HTTP 캐시 수명을 계산한다.
 * 노선당 한 개만 두므로 크기는 노선 수를 넘지 않는다. {@code board.read-cache.enabled=false}면 아무것도 두지 않는다.
 */
@Component
public class BoardReadCache {

    private final boolean enabled;
    private final Counter hits;
    private final Counter misses;
    private final Map<String, Entry> latest = new ConcurrentHashMap<>();

    BoardReadCache(@Value("${board.read-cache.enabled:true}") final boolean enabled, MeterRegistry meters) {
        this.enabled = enabled;
        this.hits = counterOf(meters, "hit");
        this.misses = counterOf(meters, "miss");
    }

    private static Counter counterOf(MeterRegistry meters, String result) {
        if (meters == null) {
            return null;
        }
        return Counter.builder("board.read.cache").tag("result", result).register(meters);
    }

    Board find(String routeId, BoardSnapshot snapshot) {
        if (!enabled) {
            return null;
        }
        Entry entry = latest.get(routeId);
        if (entry == null || !entry.snapshot().equals(snapshot)) {
            count(misses);
            return null;
        }
        count(hits);
        return entry.board();
    }

    void put(String routeId, BoardSnapshot snapshot, Board board) {
        if (enabled) {
            latest.put(routeId, new Entry(snapshot, board));
        }
    }

    private static void count(Counter counter) {
        if (counter != null) {
            counter.increment();
        }
    }

    private record Entry(BoardSnapshot snapshot, Board board) {
    }
}
