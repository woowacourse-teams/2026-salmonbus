package com.gustler.backend.api.station.application;

import com.gustler.backend.station.contract.StationTileId;

/**
 * 목록 응답을 막지 않는 제한된 내부 갱신 요청. 접수 실패해도 저장 자료를 계속 제공한다.
 */
public interface StationRefreshGateway {

    void request(StationTileId tile);
}
