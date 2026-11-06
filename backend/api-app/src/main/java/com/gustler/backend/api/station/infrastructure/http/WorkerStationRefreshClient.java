package com.gustler.backend.api.station.infrastructure.http;

import com.gustler.backend.api.station.application.StationRefreshGateway;
import com.gustler.backend.station.contract.StationTileId;
import com.gustler.backend.station.contract.StationTileRefresh;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * 유한 실행 큐·최근 요청 캐시로 GET 응답을 막거나 같은 칸의 내부 요청을 쌓지 않는다.
 */
public final class WorkerStationRefreshClient implements StationRefreshGateway, AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkerStationRefreshClient.class);
    private final RestClient rest;
    private final String token;
    private final Clock clock;
    private final ThreadPoolExecutor executor;
    private final LinkedHashMap<StationTileId, Instant> requested = new LinkedHashMap<>();

    public WorkerStationRefreshClient(
        RestClient rest,
        String token,
        Clock clock,
        ThreadPoolExecutor executor
    ) {
        this.rest = rest;
        this.token = token;
        this.clock = clock;
        this.executor = executor;
    }

    @Override
    public synchronized void request(StationTileId tile) {
        Instant now = clock.instant();
        Instant previous = requested.get(tile);
        if (previous != null && previous.plusSeconds(60).isAfter(now)) {
            return;
        }
        requested.put(tile, now);
        while (requested.size() > 1024) {
            requested.remove(requested.keySet().iterator().next());
        }
        try {
            executor.execute(() -> send(tile, now));
        } catch (RejectedExecutionException full) {
            requested.remove(tile, now);
        }
    }

    private void send(StationTileId tile, Instant requestedAt) {
        boolean accepted = false;
        try {
            StationTileRefresh result = rest.post()
                .uri("/internal/stations/refresh")
                .header("X-Station-Token", token)
                .body(new StationTileRefresh.Request(14, tile.x(), tile.y()))
                .retrieve()
                .body(StationTileRefresh.class);
            accepted = wasAccepted(result);
        } catch (RuntimeException unavailable) {
            LOGGER.debug(
                "event=station_refresh_unavailable tileX={} tileY={} exceptionType={}",
                tile.x(),
                tile.y(),
                unavailable.getClass().getSimpleName()
            );
        } finally {
            if (!accepted) {
                synchronized (this) {
                    requested.remove(tile, requestedAt);
                }
            }
        }
    }

    private static boolean wasAccepted(StationTileRefresh reply) {
        if (reply == null || reply.status() == null) {
            return false;
        }
        return reply.status() != StationTileRefresh.Status.BUSY;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
