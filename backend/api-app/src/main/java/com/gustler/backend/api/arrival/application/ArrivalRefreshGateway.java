package com.gustler.backend.api.arrival.application;

import com.gustler.backend.arrival.contract.ArrivalRefresh;
import java.time.Duration;

/**
 * 정류장 수집 접수와 제한된 완료 대기를 Worker에 요청하는 경계다.
 * 기존 수신 결과의 유효성 판단은 조회 정책이 맡는다.
 */
public interface ArrivalRefreshGateway {
    /**
     * worker에 정류장 갱신을 요청하고 접수·진행·거부 상태를 반환한다. 요청 접수가 수신 완료를 뜻하지는 않는다.
     */
    ArrivalRefresh request(String stationId);

    /**
     * worker에 정류장 갱신을 요청하고 접수·진행·거부 상태를 반환한다. 요청 접수가 수신 완료를 뜻하지는 않는다.
     */
    default ArrivalRefresh request(String stationId, Duration maximumWait) {
        return request(stationId);
    }
}
