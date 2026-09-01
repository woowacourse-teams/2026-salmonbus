package com.gustler.backend.processor.seatdistribution;

/**
 * 같은 날 · 같은 노선 · 같은 예보 거리에서 이미 도착이 확정된 예보들의 성적.
 *
 * <p>예보 시각보다 뒤에 도착한 것과 다른 날짜 것이 섞이면 아직 모르는 것을 알고 쓰는 셈이 된다.
 * 잘라 내는 것은 이 값을 만드는 쪽의 몫이고, 여기서는 이미 잘린 값만 받는다.
 *
 * @param rowCount 확정된 예보 수
 * @param actualFullCount 그중 실제로 만석이었던 수
 * @param averageRawFullChance 그 예보들의 보정 전 만석 확률 평균
 */
public record SameDayFullOutcomes(
    int rowCount,
    int actualFullCount,
    double averageRawFullChance
) {

    public SameDayFullOutcomes {
        if (rowCount < 0) {
            throw new IllegalArgumentException("확정된 예보 수는 0 이상이다: " + rowCount);
        }
        if (actualFullCount < 0 || actualFullCount > rowCount) {
            throw new IllegalArgumentException(
                "실제 만석 수는 0 이상이고 확정된 예보 수를 넘지 않는다: %d, %d"
                    .formatted(actualFullCount, rowCount)
            );
        }
        if (!(averageRawFullChance >= 0.0 && averageRawFullChance <= 1.0)) {
            throw new IllegalArgumentException(
                "보정 전 만석 확률 평균은 0 과 1 사이의 수다: " + averageRawFullChance);
        }
    }
}
