package com.gustler.backend.arrivals.domain;

import com.gustler.backend.arrival.contract.ArrivalSnapshot.RouteArrival;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 정류장별 외부 도착정보 수신을 성공·실패 결과로 반환한다.
 * 해석 실패를 빈 정상 예측으로 바꾸지 않는다.
 */
public interface ArrivalSource {
    /**
     * 선택한 키 별칭으로 원천 정류장 도착정보를 호출한다. 정상 수신과 원천 실패를 구별하는 결과를 반환한다.
     */
    Result fetch(String stationId, String keyAlias);

    sealed interface Result permits Success, Failed {
    }
    record Success(Instant receivedAt, String sourceQueryTime, List<RouteArrival> routes) implements Result {
        public Success {
            routes = List.copyOf(routes);
        }
    }
    record Failed(Reason reason, Duration retryAfter) implements Result {
        public Failed(Reason reason) {
            this(reason, null);
        }
    }
    enum Reason {
        TRANSPORT_ERROR, UPSTREAM_ERROR, UNREADABLE_RESPONSE, DAILY_QUOTA_EXCEEDED,
        RATE_LIMITED, KEY_REJECTED
    }
}
