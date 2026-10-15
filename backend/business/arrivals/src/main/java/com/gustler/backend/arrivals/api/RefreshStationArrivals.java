package com.gustler.backend.arrivals.api;

import com.gustler.backend.arrival.contract.ArrivalRefresh;

public interface RefreshStationArrivals {
    ArrivalRefresh request(String stationId);
}
