package com.gustler.backend.api.boarding.application;

import com.gustler.backend.api.boarding.domain.AccessMode;
import com.gustler.backend.api.boarding.domain.BoardingOptions.Access;
import com.gustler.backend.api.boarding.domain.StopVisit;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 기준 방문에서 후보 방문들까지 가는 방법을 한 가지 방식으로 계산한다. 새 이동 방식은 이 구현을 빈으로 더해 붙인다.
 *
 * <p>후보 전체를 한 번에 받으므로 구현은 한 번의 조회나 경로 계산으로 처리할 수 있다.
 * 계산기 단위는 구간 수단이 아니라 가는 방법의 종류다. 대중교통 방식은 도보 구간도 함께 만든다.
 */
public interface AccessFinder {
    /**
     * 이 계산기가 만드는 구간에 들어가는 수단. 요청한 수단이 이를 모두 포함할 때만 쓴다.
     */
    Set<AccessMode> requiredModes();

    /**
     * 후보마다 가는 방법을 돌려준다. 갈 수 없거나 상한을 넘는 후보는 결과에 없다.
     * 키는 {@code targets}로 받은 방문 그대로다.
     */
    Map<StopVisit, List<Access>> find(StopVisit origin, List<StopVisit> targets, AccessConstraints constraints);
}
