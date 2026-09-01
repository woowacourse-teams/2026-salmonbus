package com.gustler.backend.processor;

/**
 * (노선 판본, 정류장, 시간대) 셀 하나의 경험 통계.
 *
 * <p>원값만 든다. z값은 같은 세대의 행 전부를 놓고 그때 유도한다. 평균과 표준편차를 상수로
 * 저장하면 통계가 갱신될 때 그 상수가 뒤처져 z 가 어긋난다.
 *
 * <p>승차할 수 없는 정류장에는 셀을 만들지 않는다. 경유 지점은 아무도 안 타는 고속 구간이라
 * 그 값이 앞 정류장 승차의 결과일 뿐이고, 체계적으로 더 차 있어서 z 모집단에 섞이면
 * 승차 정류장의 값을 통째로 아래로 누른다.
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
