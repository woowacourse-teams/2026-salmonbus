package com.gustler.backend.api.salmon.application;

import com.gustler.backend.api.salmon.domain.Probabilities.ProbabilitySupply;
import com.gustler.backend.api.salmon.domain.StopLists.StopList;
import java.time.Instant;

/** 현재 정류장 목록의 탑승 확률 입력. 제품 연결은 SeatForecastProbabilitySource.probabilitiesFor이다. */
@FunctionalInterface
public interface BaselineProbabilitySource {
    ProbabilitySupply probabilitiesFor(String routeId, String stationId, int stopSequence, StopList list,
                                       Instant decisionAt);
}
