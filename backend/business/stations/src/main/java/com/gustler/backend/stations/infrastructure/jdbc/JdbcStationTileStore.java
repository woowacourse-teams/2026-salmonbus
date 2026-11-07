package com.gustler.backend.stations.infrastructure.jdbc;

import com.gustler.backend.station.contract.StationTileId;
import com.gustler.backend.station.contract.StationTileProbes;
import com.gustler.backend.stations.domain.BusStation;
import com.gustler.backend.stations.domain.StationTileStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 칸 목록·진행률·정류장 원본을 저장한다. 원천 호출 결과 하나를 짧은 트랜잭션 하나로 쓴다.
 *
 * <p>쓰기 트랜잭션은 2초, 잠금 대기는 0.5초로 묶어 worker의 다른 작업이 쓰는 DB 연결을 오래 쥐지 않는다.
 */
public class JdbcStationTileStore implements StationTileStore {

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public JdbcStationTileStore(JdbcClient jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
    }

    @Override
    public Set<StationTileId> salmonTiles() {
        List<double[]> points = jdbc.sql("""
            SELECT location.x, location.y FROM route_stop stop
            JOIN route_version version ON version.id = stop.route_version_id AND version.valid_to IS NULL
            JOIN route route ON route.id = version.route_id AND route.source_id = 'GBIS'
            JOIN LATERAL (SELECT x, y FROM route_stop_location_history history
                WHERE history.route_version_id = stop.route_version_id AND history.stop_order = stop.stop_order
                ORDER BY history.observed_at DESC LIMIT 1) location ON true
            WHERE stop.boarding_allowed
            """).query((row, index) -> new double[] { row.getDouble(1), row.getDouble(2) }).list();
        Set<StationTileId> tiles = new HashSet<>();
        for (double[] point : points) {
            tiles.add(StationTileId.of(point[0], point[1]));
        }
        return tiles;
    }

    @Override
    public int enqueue(Set<StationTileId> tiles, final int priority, Instant now) {
        Integer added = transactions.execute(status -> {
            limitTransaction();
            int count = 0;
            for (StationTileId tile : tiles) {
                if (!tile.inServiceArea()) {
                    continue;
                }
                count += jdbc.sql("""
                    INSERT INTO bus_station_tile (tile_x, tile_y, priority, queued_at)
                    VALUES (?, ?, ?, ?) ON CONFLICT (tile_x, tile_y) DO NOTHING
                    """).params(tile.x(), tile.y(), priority, Timestamp.from(now)).update();
            }
            return count;
        });
        if (added == null) {
            return 0;
        }
        return added;
    }

    @Override
    public Optional<StationTileStore.TilePass> next(Instant now, Instant refreshBefore) {
        Optional<StationTileStore.TilePass> running = jdbc.sql("""
            SELECT tile_x, tile_y, pass_started_at, probes_done FROM bus_station_tile
            WHERE refresh_requested_at IS NOT NULL AND pass_started_at IS NOT NULL
              AND (completed_pass_started_at IS NULL OR pass_started_at > completed_pass_started_at)
              AND (retry_after_at IS NULL OR retry_after_at <= ?)
            ORDER BY priority, queued_at LIMIT 1
            """)
            .param(Timestamp.from(now))
            .query((row, index) -> new StationTileStore.TilePass(
                new StationTileId(row.getInt(1), row.getInt(2)),
                row.getTimestamp(3).toInstant(),
                row.getInt(4)
            ))
            .optional();
        if (running.isPresent()) {
            return running;
        }
        Optional<StationTileId> waiting = jdbc.sql("""
            SELECT tile_x, tile_y FROM bus_station_tile
            WHERE refresh_requested_at IS NOT NULL
              AND (completed_at IS NULL OR pass_started_at = completed_pass_started_at)
              AND (pass_started_at IS NULL OR completed_at IS NOT NULL)
              AND (retry_after_at IS NULL OR retry_after_at <= ?)
            ORDER BY priority, queued_at LIMIT 1
            """)
            .param(Timestamp.from(now))
            .query((row, index) -> new StationTileId(row.getInt(1), row.getInt(2)))
            .optional();
        return waiting.map(tile -> startPass(tile, now));
    }

    private StationTileStore.TilePass startPass(StationTileId tile, Instant now) {
        transactions.executeWithoutResult(status -> {
            limitTransaction();
            jdbc.sql(
                """
                UPDATE bus_station_tile SET pass_started_at = ?, probes_done = 0, failure_reason = NULL,
                    retry_after_at = NULL, failure_count = 0
                WHERE tile_x = ? AND tile_y = ?
                """
            )
                .params(
                    Timestamp.from(now),
                    tile.x(),
                    tile.y()
                )
                .update();
        });
        return new StationTileStore.TilePass(tile, now, 0);
    }

    @Override
    public void recordProbe(StationTileStore.TilePass pass, List<BusStation> stations, Instant seenAt) {
        transactions.executeWithoutResult(status -> {
            limitTransaction();
            for (BusStation station : stations) {
                upsert(station, seenAt);
            }
            final int done = pass.probesDone() + 1;
            final int updated = jdbc.sql("""
                UPDATE bus_station_tile SET probes_done = ?, failure_reason = NULL, retry_after_at = NULL,
                    failure_count = 0
                WHERE tile_x = ? AND tile_y = ? AND pass_started_at = ? AND probes_done = ?
                """)
                .params(
                    done,
                    pass.tile().x(),
                    pass.tile().y(),
                    Timestamp.from(pass.passStartedAt()),
                    pass.probesDone()
                )
                .update();
            if (updated != 1) {
                // 같은 칸을 다른 실행이 이미 진행했다. 이번 결과는 버린다.
                status.setRollbackOnly();
                return;
            }
            if (done == StationTileProbes.COUNT) {
                complete(pass, seenAt);
            }
        });
    }

    private void upsert(BusStation station, Instant seenAt) {
        StationTileId tile = station.tile();
        jdbc.sql("""
            INSERT INTO bus_station AS s (station_id, name, x, y, mobile_no, region_name, center_lane,
                                          tile_x, tile_y, first_seen_at, last_seen_at, verified_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (station_id) DO UPDATE SET
                name = EXCLUDED.name, x = EXCLUDED.x, y = EXCLUDED.y, mobile_no = EXCLUDED.mobile_no,
                region_name = EXCLUDED.region_name, center_lane = EXCLUDED.center_lane,
                tile_x = EXCLUDED.tile_x, tile_y = EXCLUDED.tile_y,
                last_seen_at = GREATEST(s.last_seen_at, EXCLUDED.last_seen_at),
                verified_at = EXCLUDED.last_seen_at, active = true
            """)
            .params(
                station.stationId(),
                station.name(),
                station.x(),
                station.y(),
                station.mobileNo(),
                station.regionName(),
                station.centerLane(),
                tile.x(),
                tile.y(),
                Timestamp.from(seenAt),
                Timestamp.from(seenAt),
                Timestamp.from(seenAt)
            )
            .update();
    }

    private void complete(StationTileStore.TilePass pass, Instant now) {
        StationTileId tile = pass.tile();
        // 12개 탐색점이 모두 정상 완료된 경우에만 누락 정류장을 비활성화한다.
        // 중간 실패·재시도 동안에는 CSV/이전 성공 자료를 계속 보여 준다.
        jdbc.sql("""
            UPDATE bus_station SET active = false, verified_at = ?
            WHERE tile_x = ? AND tile_y = ? AND (verified_at IS NULL OR last_seen_at < ?)
            """).params(Timestamp.from(now), tile.x(), tile.y(), Timestamp.from(pass.passStartedAt())).update();
        final int count = jdbc.sql("SELECT count(*) FROM bus_station WHERE tile_x = ? AND tile_y = ? AND active")
            .params(tile.x(), tile.y())
            .query(Integer.class)
            .single();
        jdbc.sql(
            """
            UPDATE bus_station_tile SET completed_pass_started_at = pass_started_at, completed_at = ?,
                station_count = ?, refresh_requested_at = NULL
            WHERE tile_x = ? AND tile_y = ?
            """
        )
            .params(
                Timestamp.from(now),
                count,
                tile.x(),
                tile.y()
            )
            .update();
    }

    @Override
    public int fail(StationTileStore.TilePass pass, String reason, Instant retryAfter) {
        Integer failures = transactions.execute(status -> {
            limitTransaction();
            return jdbc.sql(
                """
                UPDATE bus_station_tile SET failure_reason = ?, retry_after_at = ?, failure_count = failure_count + 1
                WHERE tile_x = ? AND tile_y = ? AND pass_started_at = ?
                RETURNING failure_count
                """
            )
                .params(
                    abbreviateReason(reason),
                    Timestamp.from(retryAfter),
                    pass.tile().x(),
                    pass.tile().y(),
                    Timestamp.from(pass.passStartedAt())
                )
                .query(Integer.class)
                .optional()
                .orElse(0);
        });
        if (failures == null) {
            return 0;
        }
        return failures;
    }

    @Override
    public void giveUp(StationTileStore.TilePass pass, String reason, Instant retryAfter) {
        transactions.executeWithoutResult(status -> {
            limitTransaction();
            // 진행 중 회차를 마지막 완료 회차로 되돌려 다음 접수가 처음 탐색점부터 새 회차를 열게 한다.
            jdbc.sql(
                """
                UPDATE bus_station_tile SET refresh_requested_at = NULL, pass_started_at = completed_pass_started_at,
                    probes_done = 0, failure_count = 0, failure_reason = ?, retry_after_at = ?
                WHERE tile_x = ? AND tile_y = ? AND pass_started_at = ?
                """
            )
                .params(
                    abbreviateReason(reason),
                    Timestamp.from(retryAfter),
                    pass.tile().x(),
                    pass.tile().y(),
                    Timestamp.from(pass.passStartedAt())
                )
                .update();
        });
    }

    private static String abbreviateReason(String reason) {
        if (reason.length() > 40) {
            return reason.substring(0, 40);
        }
        return reason;
    }

    private void limitTransaction() {
        jdbc.sql("SET LOCAL statement_timeout = '2s'").update();
        jdbc.sql("SET LOCAL lock_timeout = '500ms'").update();
    }
}
