package com.gustler.backend.arrivals.api;

import com.gustler.backend.arrival.contract.ArrivalRefresh;
import java.time.Duration;

/**
 * 정류장 도착정보 수집을 접수하고 필요하면 한도 내에서 완료를 기다리는 업무 경계다.
 */
public interface RefreshStationArrivals {
    /**
     * 정류장 갱신을 요청한다. 반환 상태는 접수·진행·거부를 구별하며 완료된 수신 결과와는 별개다.
     */
    ArrivalRefresh request(String stationId);

    default ArrivalRefresh requestAndAwait(String stationId, Duration maximumWait) {
        return request(stationId);
    }
}
