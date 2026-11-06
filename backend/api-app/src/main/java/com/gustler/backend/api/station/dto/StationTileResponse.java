package com.gustler.backend.api.station.dto;

import com.gustler.backend.api.station.domain.MapStation;
import com.gustler.backend.api.station.domain.StationTile;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 줌 14 칸 하나의 정류장 응답. 도착정보는 넣지 않는다.
 */
@Schema(name = "StationTileResponse")
public record StationTileResponse(
    @Schema(description = "줌 14 칸 번호") Tile tile,
    @Schema(
        description = "READY는 이 칸이 기초 명부에 포함됐거나 칸 전체 조회에 성공함. 최신 전수 검증 보장이 아니며 명부 경계에 걸친 칸은 일부만 담길 수 있음. PENDING은 이 칸의 목록을 아직 확보하지 못함. 이웃 칸 조회로 저장된 일부 정류장이 있을 수 있고 수집 범위 밖 칸도 PENDING",
        allowableValues = { "READY", "PENDING" }
    ) String status,
    @Schema(description = "정류장 원천") String source,
    @Schema(description = "좌표계") String coordinateSystem,
    @Schema(description = "칸 안 정류장. stationId 오름차순") List<Station> stations,
    @Schema(description = "설치된 공식 기초 명부 기준일. 없으면 null", nullable = true) LocalDate baselineDate,
    @Schema(description = "이 칸의 마지막 전체 실조회 성공 시각. 명부 적재 시각과 별개", nullable = true) Instant verifiedAt
) {

    public StationTileResponse {
        stations = List.copyOf(stations);
    }

    public static StationTileResponse from(StationTile tile) {
        return new StationTileResponse(
            new Tile(14, tile.id().x(), tile.id().y()),
            statusOf(tile),
            "GBIS",
            "WGS84",
            tile.stations()
                .stream()
                .map(StationTileResponse::stationOf)
                .toList(),
            tile.baselineDate(),
            tile.verifiedAt()
        );
    }

    private static String statusOf(StationTile tile) {
        if (tile.ready()) {
            return "READY";
        }
        return "PENDING";
    }

    private static Station stationOf(MapStation station) {
        return new Station(
            station.stationId(),
            station.name(),
            new Point(station.x(), station.y()),
            station.mobileNo(),
            station.regionName(),
            station.direction(),
            station.salmonRouteIds()
        );
    }

    public record Tile(int zoom, int x, int y) {
    }

    public record Point(double x, double y) {
    }

    public record Station(
        @Schema(description = "GBIS 정류장 ID") String stationId,
        @Schema(description = "정류장 이름. 우리 노선 정류장은 노선 자료의 이름") String name,
        @Schema(description = "WGS84 좌표") Point point,
        @Schema(description = "정류소번호. 없으면 null", nullable = true) String mobileNo,
        @Schema(description = "지역명. 우리 노선 정류장만 있고 원본이 없으면 null", nullable = true) String regionName,
        @Schema(
            description = "같은 이름 정류장을 구별하는 방면(다음 정차 정류장 이름). 우리 노선이 없는 정류장은 null",
            nullable = true
        ) String direction,
        @Schema(description = "이 정류장을 지나는 우리 노선. 비면 예보 노선이 없는 정류장") List<String> salmonRouteIds
    ) {

        public Station {
            salmonRouteIds = List.copyOf(salmonRouteIds);
        }
    }
}
