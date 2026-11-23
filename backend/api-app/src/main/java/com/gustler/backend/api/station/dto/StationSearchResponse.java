package com.gustler.backend.api.station.dto;

import com.gustler.backend.api.station.domain.MapStation;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 정류장 이름 검색 결과. 정류장 형태는 지도 칸 응답과 같다.
 */
@Schema(name = "StationSearchResponse")
public record StationSearchResponse(
    @Schema(description = "검색된 정류장. 최대 30개. 이름이 같은 정도, 우리 노선 정류장, 이름, stationId 순") List<StationTileResponse.Station> stations
) {

    public StationSearchResponse {
        stations = List.copyOf(stations);
    }

    public static StationSearchResponse from(List<MapStation> stations) {
        return new StationSearchResponse(stations.stream().map(StationTileResponse.Station::from).toList());
    }
}
