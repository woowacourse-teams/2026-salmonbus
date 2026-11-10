package com.gustler.backend.api.salmon.application;

import com.gustler.backend.api.salmon.domain.ActionJudge.Enumeration;
import java.time.Instant;

/**
 * 이동 후보를 나열하는 포트. 후보 조립과 우회량 계산은 이 포트의 구현이 맡고, 판정 호출부는 결과만 받는다.
 */
@FunctionalInterface
public interface SalmonCandidateRoutes {
    Enumeration enumerate(String routeId, String stationId, int stopSequence, Instant decisionAt, String epoch);
}
