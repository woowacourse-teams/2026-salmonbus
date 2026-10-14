package com.gustler.backend.api.arrival;

import com.gustler.backend.arrival.ArrivalRefresh;

public interface ArrivalRefreshGateway {
    ArrivalRefresh request(String stationId);
}
