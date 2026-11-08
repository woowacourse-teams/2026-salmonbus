package com.gustler.backend.api.salmon.application;

import com.gustler.backend.api.salmon.domain.ActionModel.MissingReason;
import com.gustler.backend.api.salmon.domain.ActionModel.StopList;
import com.gustler.backend.api.salmon.domain.ActionModel.Unavailable;
import java.time.Instant;

public interface StopArrivalSource {
    StopList arrivalsAt(String routeId, String stationId, int stopSequence, Instant decisionAt);

    static StopArrivalSource unavailable() {
        return (routeId, stationId, stopSequence, decisionAt) -> new Unavailable(MissingReason.NOT_PROVIDED);
    }
}
