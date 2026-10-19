package com.gustler.backend.arrivals.api;

import com.gustler.backend.arrival.contract.ArrivalRefresh;
import java.time.Duration;

public interface RefreshStationArrivals {
    ArrivalRefresh request(String stationId);

    default ArrivalRefresh requestAndAwait(String stationId, Duration maximumWait) {
        return request(stationId);
    }
}
