package com.gustler.backend.api.salmon.application;

import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.salmon.domain.IntegratedJudge.Situation;

/** 켜진 공개 조회가 비교할 상황. 테스트는 대기 근거가 없는 상황을 그대로 돌려준다. */
public interface SalmonActionCase {
    Situation situation(RouteId routeId, String stationId, int stopSequence);
}
