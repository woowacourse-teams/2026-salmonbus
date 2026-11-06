package com.gustler.backend.api.station.application;

import com.gustler.backend.api.error.ServiceUnavailableException;
import com.gustler.backend.api.station.InvalidStationTileException;
import com.gustler.backend.api.station.domain.StationTile;
import com.gustler.backend.station.contract.StationTileId;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Service;

/**
 * 저장된 명부를 즉시 반환하고 필요한 칸의 갱신 접수는 응답과 분리한다. 원천은 worker만 부른다.
 *
 * <p>수집 경계 상자 밖의 칸은 저장 자료만 돌려주고 갱신을 접수하지 않는다.
 */
@Service
public class StationTileQueryService {

    private final StationReadRepository repository;
    private final StationRefreshGateway refresh;
    private final Clock clock;
    private final Duration freshness;

    public StationTileQueryService(
        StationReadRepository repository,
        StationRefreshGateway refresh,
        Clock clock,
        @Value("${stations.refresh.interval:30d}") Duration freshness
    ) {
        this.repository = repository;
        this.refresh = refresh;
        this.clock = clock;
        this.freshness = freshness;
    }

    public StationTile get(final int zoom, final int x, final int y) {
        if (zoom != StationTileId.ZOOM) {
            throw new InvalidStationTileException();
        }
        StationTileId id;
        try {
            id = new StationTileId(x, y);
        } catch (IllegalArgumentException invalid) {
            throw new InvalidStationTileException();
        }
        try {
            StationTile tile = repository.tile(id);
            if (id.inServiceArea() && isStale(tile)) {
                refresh.request(id);
            }
            return tile;
        } catch (DataAccessResourceFailureException unavailable) {
            throw new ServiceUnavailableException();
        }
    }

    private boolean isStale(StationTile tile) {
        if (tile.verifiedAt() == null) {
            return true;
        }
        return tile.verifiedAt().isBefore(clock.instant().minus(freshness));
    }
}
