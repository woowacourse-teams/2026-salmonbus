package com.gustler.backend.api.salmon.application;

import com.gustler.backend.api.salmon.domain.Fields.MissingReason;
import com.gustler.backend.api.salmon.domain.StopLists.StopList;
import com.gustler.backend.api.salmon.domain.StopLists.Unavailable;
import java.time.Instant;

/** 정류장 도착 목록을 주는 포트. */
public interface StopArrivalSource {
    StopList arrivalsAt(String routeId, String stationId, int stopSequence, Instant decisionAt);

    static StopArrivalSource unavailable() {
        return (routeId, stationId, stopSequence, decisionAt) -> new Unavailable(MissingReason.NOT_PROVIDED);
    }
}
