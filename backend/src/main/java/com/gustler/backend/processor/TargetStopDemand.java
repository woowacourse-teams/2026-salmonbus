package com.gustler.backend.processor;

/**
 * 예보 대상 정류장이 평소 얼마나 붐볐나. 셀 통계에서 이 예보에 필요한 것만 뽑는다.
 *
 * <p>셋 다 대상 정류장 순번에서 나온다. 자리가 얼마나 차 있었는지와, 대상까지 지나갈 구간에서
 * 얼마나 탔는지와, 그 정류장 통계가 없어 이웃 정류장으로 메웠는지다.
 *
 * <p>셀 통계는 한 세대를 통째로 들고 평균 대비 위치를 재는 일과 이웃으로 메우는 일과 구간을 합치는
 * 일을 자기 안에서 닫는다. 여기서는 <b>무엇을 물어볼지</b>만 정한다. 그 물음이 대상 정류장과
 * 지평에서 나오므로 짝짓는 자리를 한 곳에 둔다.
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
        VehicleStopTarget target
    ) {
        final int targetStopOrder = target.stopOrder();
        return new TargetStopDemand(
            statistics.fillRateScoreAt(targetStopOrder),
            statistics.netBoardingSegmentScoreOf(targetStopOrder, target.distance().stopCount()),
            statistics.isFilledByNeighboursAt(targetStopOrder));
    }
}
