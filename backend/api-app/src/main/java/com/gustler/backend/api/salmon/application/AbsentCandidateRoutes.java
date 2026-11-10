package com.gustler.backend.api.salmon.application;

import com.gustler.backend.api.salmon.domain.ActionJudge.Enumeration;
import com.gustler.backend.api.salmon.domain.ActionJudge.Failed;
import com.gustler.backend.api.salmon.domain.Fields.MissingReason;
import java.time.Instant;

/** 후보 연결이 없을 때의 결측. 빈 목록은 후보가 없다는 판정이므로 쓰지 않는다. */
public final class AbsentCandidateRoutes implements SalmonCandidateRoutes {
    @Override
    public Enumeration enumerate(String routeId, String stationId, int stopSequence, Instant decisionAt, String epoch) {
        return new Failed(MissingReason.NOT_PROVIDED);
    }
}
