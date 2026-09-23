package com.gustler.backend.forecasting.domain.statistics;

import java.time.Instant;
import java.util.List;

/**
 * 정류장별 수요 통계를 조회하고 새 통계 버전을 저장한다.
 *
 * <p>표준화, 인접 정류장 보완, 통과 구간의 합계 계산에는 같은 버전의 여러 정류장 통계가 필요하다.
 * 따라서 정류장 하나씩 조회하지 않고 노선 버전과 시간대별 통계를 함께 조회한다.
 */
public interface StopDemandStatisticsRepository {

    /**
     * 자료 기준 시각이 관측 시각 이하인 통계 중 가장 최근 버전을 조회한다.
     *
     * <p>수집 배치를 늦게 처리하더라도 관측 시각 이후의 자료로 계산한 통계를 사용하지 않는다.
     */
    StopDemandStatistics readAsOf(
        long routeVersionId,
        TimeSlot timeSlot,
        String calculationVersion,
        Instant observedAt
    );

    /** 현재 통계 버전. 집계한 적이 없으면 0을 반환한다. */
    int currentRevision(
        long routeVersionId,
        String calculationVersion
    );

    /**
     * 확정된 평가 결과를 정류장과 시간별로 집계한다.
     *
     * <p>승차 가능한 정류장의 한 정류장 앞 예측만 집계한다. 같은 도착 결과가 여러 거리의 예측에
     * 반영되므로, 대상 정류장까지 남은 거리를 제한해 중복 집계를 막는다.
     */
    List<StopDemandHourlyTotals> readHourlyTotals(
        long routeVersionId,
        Instant dataUntil
    );

    /**
     * 새 통계 버전을 추가하고 이전 버전은 보존한다.
     *
     * <p>지연된 수집 배치에도 관측 시각에 맞는 통계를 적용할 수 있도록 기존 결과를 덮어쓰지 않는다.
     */
    void append(
        DemandStatisticsVersion statisticsVersion
    );
}
