package com.gustler.backend.api.boarding.application;

import com.gustler.backend.api.boarding.domain.StopVisit;
import java.util.List;
import java.util.Optional;

/**
 * 탑승 후보 계산에 필요한 현재 노선 판본과 그 방문을 읽는다.
 */
public interface BoardingOptionsQueryRepository {
    Optional<Long> currentVersion(String routeId);

    List<StopVisit> visits(long versionId);
}
