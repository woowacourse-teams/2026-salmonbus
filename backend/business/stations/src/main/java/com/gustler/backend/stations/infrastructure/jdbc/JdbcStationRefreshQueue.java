package com.gustler.backend.stations.infrastructure.jdbc;

import com.gustler.backend.station.contract.StationTileId;
import com.gustler.backend.station.contract.StationTileRefresh;
import com.gustler.backend.stations.api.RequestStationTileRefresh;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 다중 API/worker 접수도 하나의 DB 잠금으로 중복·대기열 상한을 지킨다. 원천은 부르지 않는다.
 */
public final class JdbcStationRefreshQueue implements RequestStationTileRefresh {

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Duration freshness;
    private final int capacity;

    public JdbcStationRefreshQueue(
        JdbcClient jdbc,
        PlatformTransactionManager manager,
        Clock clock,
        Duration freshness,
        final int capacity
    ) {
        if (capacity < 1 || hasInvalidFreshness(freshness)) {
            throw new IllegalArgumentException("정류장 갱신 대기열과 유효기간을 확인해야 한다");
        }
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.clock = clock;
        this.freshness = freshness;
        this.capacity = capacity;
    }

    /**
     * 수집 경계 상자 밖의 칸은 저장하지 않고 FRESH로 답한다. 채울 대상이 아니라 다시 요청할 필요가 없다는 뜻이다.
     * BUSY는 나중에 다시 요청하라는 뜻이라 쓰지 않는다.
     */
    @Override
    public StationTileRefresh request(StationTileId tile) {
        if (!tile.inServiceArea()) {
            return new StationTileRefresh(StationTileRefresh.Status.FRESH);
        }
        StationTileRefresh.Status result = transactions.execute(status -> {
            jdbc.sql("SET LOCAL lock_timeout = '500ms'").update();
            jdbc.sql("SET LOCAL statement_timeout = '2s'").update();
            if (!jdbc.sql("SELECT pg_try_advisory_xact_lock(20261106, 49)").query(Boolean.class).single()) {
                return StationTileRefresh.Status.BUSY;
            }
            Instant now = clock.instant();
            Optional<JdbcStationRefreshQueue.State> existing = jdbc.sql(
                "SELECT completed_at, refresh_requested_at FROM bus_station_tile WHERE tile_x = ? AND tile_y = ?"
            )
                .params(tile.x(), tile.y())
                .query((row, index) -> new State(row.getTimestamp(1), row.getTimestamp(2)))
                .optional();
            if (existing.filter(State::isPending).isPresent()) {
                return StationTileRefresh.Status.JOINED;
            }
            if (existing.filter(state -> state.isFresh(now.minus(freshness))).isPresent()) {
                return StationTileRefresh.Status.FRESH;
            }
            final int pending = jdbc.sql("SELECT count(*) FROM bus_station_tile WHERE refresh_requested_at IS NOT NULL")
                .query(Integer.class)
                .single();
            if (pending >= capacity) {
                return StationTileRefresh.Status.BUSY;
            }
            jdbc.sql(
                """
                INSERT INTO bus_station_tile(tile_x, tile_y, priority, queued_at, requested_at, refresh_requested_at)
                VALUES (?, ?, 0, ?, ?, ?)
                ON CONFLICT(tile_x, tile_y) DO UPDATE SET priority = 0, requested_at = EXCLUDED.requested_at,
                    refresh_requested_at = EXCLUDED.refresh_requested_at
                """
            )
                .params(
                    tile.x(),
                    tile.y(),
                    Timestamp.from(now),
                    Timestamp.from(now),
                    Timestamp.from(now)
                )
                .update();
            return StationTileRefresh.Status.QUEUED;
        });
        return new StationTileRefresh(result);
    }

    private static boolean hasInvalidFreshness(Duration freshness) {
        return freshness.isNegative() || freshness.isZero();
    }

    private record State(Timestamp completed, Timestamp pending) {

        boolean isPending() {
            return pending != null;
        }

        boolean isFresh(Instant refreshAfter) {
            if (completed == null) {
                return false;
            }
            return !completed.toInstant().isBefore(refreshAfter);
        }
    }
}
