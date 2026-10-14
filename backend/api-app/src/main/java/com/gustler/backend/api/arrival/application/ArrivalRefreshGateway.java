package com.gustler.backend.api.arrival.application;

import com.gustler.backend.arrival.contract.ArrivalRefresh;

public interface ArrivalRefreshGateway {
    ArrivalRefresh request(String stationId);
}
