package com.gustler.backend.api.station;

import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;

/**
 * 정류장 지도 칸의 줌이 14가 아니거나 칸 번호가 범위를 벗어났음을 알린다.
 */
public class InvalidStationTileException extends ApiException {

    public InvalidStationTileException() {
        super(ErrorCode.INVALID_STATION_TILE);
    }
}
