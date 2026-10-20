package com.gustler.backend.forecasting.domain.statistics;

import java.time.Instant;

/**
 * 평가 결과를 정류장·시간별로 묶어 합산한 값이다.
 *
 * <p>평균이 아니라 합으로 받는다. 순승차 비율은 평균의 비율이지 비율의 평균이 아니므로
 * 시각별 평균을 다시 합치면 값이 달라진다.
 *
 * <p>시간대와 날짜는 SQL이 아닌 {@link TimeSlot} 과 주입받은 시계가 정한다.
 * SQL에서도 같은 규칙을 구현하면 두 계산의 결과가 달라질 수 있다.
 */
public record StopDemandHourlyTotals(
    int stopOrder,
    Instant arrivedHourStart,
    double fillRateTotal,
    double netBoardingTotal,
    double capacityTotal,
    int sampleCount
) {
    public StopDemandHourlyTotals {
        if (sampleCount <= 0) {
            throw new IllegalArgumentException("합친 라벨이 없으면 셀 재료가 아니다: " + sampleCount);
        }
        if (capacityTotal <= 0) {
            throw new IllegalArgumentException("정원 합은 0보다 크다: " + capacityTotal);
        }
    }
}
