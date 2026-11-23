package com.gustler.backend.api.station.application;

import com.gustler.backend.api.station.domain.MapStation;
import com.gustler.backend.api.station.domain.StationInfo;
import com.gustler.backend.api.station.domain.StationSearchQuery;
import com.gustler.backend.api.station.domain.StationTile;
import com.gustler.backend.station.contract.StationTileId;
import java.util.List;
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

    /**
     * 지도에 찍히는 정류장 중 이름 키에 검색어가 들어 있는 곳을 정류장당 한 건씩 최대 limit개 준다.
     * 이름이 같은 정도(전체 일치, 앞부분 일치, 포함) 순이고, 같은 정도에서는 우리 노선 정류장이 먼저다.
     */
    List<MapStation> search(StationSearchQuery query, int limit);
}
