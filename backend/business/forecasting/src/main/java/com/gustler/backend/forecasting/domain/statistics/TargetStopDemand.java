package com.gustler.backend.forecasting.domain.statistics;

/**
 * 예보 대상 정류장의 평소 혼잡도를 셀 통계에서 조회한다.
 *
 * <p>세 값은 모두 대상 정류장 순번을 기준으로 한다. 자리가 찬 비율, 대상까지 지날 구간의 승차량,
 * 해당 정류장 통계가 없어 이웃 정류장으로 보완했는지 여부다.
 *
 * <p>셀 통계가 한 세대의 전체 값으로 평균 대비 위치를 계산하고 이웃 보완과 구간 합산을 처리한다.
 * 여기서는 대상 정류장과 예보 거리에 따라 <b>조회할 항목</b>만 함께 선택한다.
 *
 * <p>A18 문서는 이 층을 {@code L_cell} 이라고 적었다. 셀 통계를 읽는 층이라는 뜻이다.
 */
public record TargetStopDemand(
    double fillRateScore,
    double netBoardingSegmentScore,
    boolean filledByNeighbours
) {
    public static TargetStopDemand of(
        StopDemandStatistics statistics,
        final int targetStopOrder,
        final int stopsToTarget
    ) {
        return new TargetStopDemand(
            statistics.fillRateScoreAt(targetStopOrder),
            statistics.netBoardingSegmentScoreOf(targetStopOrder, stopsToTarget),
            statistics.isFilledByNeighboursAt(targetStopOrder));
    }
}
