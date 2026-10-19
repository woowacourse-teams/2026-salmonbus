package com.gustler.backend.api.arrival.application;

import com.gustler.backend.arrival.contract.ArrivalRefresh;
import java.time.Duration;

public interface ArrivalRefreshGateway {
    ArrivalRefresh request(String stationId);

    default ArrivalRefresh request(String stationId, Duration maximumWait) {
        return request(stationId);
    }
}
