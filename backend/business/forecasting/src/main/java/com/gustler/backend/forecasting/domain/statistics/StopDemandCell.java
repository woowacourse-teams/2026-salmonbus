package com.gustler.backend.forecasting.domain.statistics;

/**
 * 노선 버전·정류장·시간대별 셀 하나의 경험 통계다.
 *
 * <p>원값만 보관한다. z값은 같은 세대의 전체 행에서 계산한다. 평균과 표준편차를 상수로
 * 저장하면 통계가 갱신된 뒤에도 이전 상수를 사용해 z 가 어긋난다.
 *
 * <p>승차할 수 없는 정류장에는 셀을 만들지 않는다. 경유 지점은 승객이 타지 않는 고속 구간이므로
 * 그 값은 앞 정류장 승차의 결과이며 체계적으로 더 차 있다. 이를 z 모집단에 섞으면
 * 승차 정류장의 값이 전체적으로 낮아진다.
 */
public record StopDemandCell(
    int stopOrder,
    double averageFillRate,
    double averageNetBoardingRate,
    int sampleCount,
    int dayCount
) {
    private static final int FIRST_STOP_ORDER = 1;

    public StopDemandCell {
        if (stopOrder < FIRST_STOP_ORDER) {
            throw new IllegalArgumentException("정류장 순번은 %d번부터다: %d".formatted(FIRST_STOP_ORDER, stopOrder));
        }
        if (!(0 <= averageFillRate && averageFillRate <= 1)) {
            throw new IllegalArgumentException("자리가 찬 비율은 0에서 1 사이다: " + averageFillRate);
        }
        if (!(-1 <= averageNetBoardingRate && averageNetBoardingRate <= 1)) {
            throw new IllegalArgumentException("순승차 비율은 -1에서 1 사이다: " + averageNetBoardingRate);
        }
        if (dayCount < 0 || sampleCount < dayCount) {
            throw new IllegalArgumentException(
                "날짜 수는 0 이상이고 표본 수를 넘지 않는다: %d, %d".formatted(dayCount, sampleCount)
            );
        }
    }
}
