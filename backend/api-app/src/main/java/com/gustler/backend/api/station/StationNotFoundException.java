package com.gustler.backend.api.station;

import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;

/**
 * 정류장 지도에 없는 정류장(정류장 원본에도 우리 노선 방문에도 없음)임을 알린다.
 */
public class StationNotFoundException extends ApiException {

    public StationNotFoundException() {
        super(ErrorCode.STATION_NOT_FOUND);
    }
}
