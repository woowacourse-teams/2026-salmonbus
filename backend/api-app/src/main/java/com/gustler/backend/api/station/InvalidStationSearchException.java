package com.gustler.backend.api.station;

import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;

/**
 * 정류장 검색어가 없거나, 기호를 빼면 비거나, 너무 길다는 것을 알린다.
 */
public class InvalidStationSearchException extends ApiException {

    public InvalidStationSearchException() {
        super(ErrorCode.INVALID_STATION_SEARCH);
    }
}
