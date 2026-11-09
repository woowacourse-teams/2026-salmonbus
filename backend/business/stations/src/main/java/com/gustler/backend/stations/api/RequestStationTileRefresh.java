package com.gustler.backend.stations.api;

import com.gustler.backend.station.contract.StationTileId;
import com.gustler.backend.station.contract.StationTileRefresh;

/**
 * 요청한 칸만 유한한 영속 대기열에 넣는다. 같은 칸의 진행 중 작업은 합류한다. 수집 경계 상자 밖의 칸은 넣지 않는다.
 */
public interface RequestStationTileRefresh {

    StationTileRefresh request(StationTileId tile);
}
