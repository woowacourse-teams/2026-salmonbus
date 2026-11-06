package com.gustler.backend.api.station.domain;

import com.gustler.backend.station.contract.StationTileId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 읽을 수 있는 저장 목록. 기초 명부 기준일과 실제 칸 검증 성공 시각은 별개다. READY는 전수 최신성 보장이 아니다.
 */
public record StationTile(
    StationTileId id,
    boolean ready,
    List<MapStation> stations,
    LocalDate baselineDate,
    Instant verifiedAt
) {

    public StationTile(StationTileId id, final boolean ready, List<MapStation> stations) {
        this(id, ready, stations, null, null);
    }

    public StationTile {
        stations = List.copyOf(stations);
    }
}
