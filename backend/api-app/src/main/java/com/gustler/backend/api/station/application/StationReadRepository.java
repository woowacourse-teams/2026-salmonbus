package com.gustler.backend.api.station.application;

import com.gustler.backend.api.station.domain.StationInfo;
import com.gustler.backend.api.station.domain.StationTile;
import com.gustler.backend.station.contract.StationTileId;
import java.util.Optional;

/**
 * 정류장 지도 조회 포트. 정류장 원본과 우리 노선 정류장을 함께 읽는다.
 */
public interface StationReadRepository {

    StationTile tile(StationTileId id);

    /**
     * 지도에 찍히는 정류장이면 정보를, 아니면 빈 값을 준다. 상세 API의 입구 조건이다.
     */
    Optional<StationInfo> info(String stationId);
}
