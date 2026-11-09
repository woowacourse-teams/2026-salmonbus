package com.gustler.backend.station.contract;

/**
 * 지도 목록 응답과 분리된 내부 수집 접수 결과. 원천 호출 완료를 기다리지 않는다.
 */
public record StationTileRefresh(Status status) {

    public record Request(int zoom, int x, int y) {

        public StationTileId tile() {
            if (zoom != StationTileId.ZOOM) {
                throw new IllegalArgumentException("정류장 칸은 줌 14여야 한다");
            }
            return new StationTileId(x, y);
        }
    }

    public enum Status {

        QUEUED, JOINED, FRESH, BUSY
    }
}
