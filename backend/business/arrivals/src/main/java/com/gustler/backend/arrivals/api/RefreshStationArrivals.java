package com.gustler.backend.arrivals.api;

import com.gustler.backend.arrival.ArrivalRefresh;

public interface RefreshStationArrivals {
    ArrivalRefresh request(String stationId);
}
