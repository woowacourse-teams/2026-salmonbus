package com.gustler.backend.forecasting.domain.evaluation;

import com.gustler.backend.forecasting.domain.model.SameDayFullOutcomes;
import java.time.Instant;

/**
 * 한 노선 · 한 날짜 · 한 예보 거리의 성적.
 *
 * <p>정산 한 건마다 더해 갱신할 수 있도록 평균이 아니라 합을 보관한다.
 *
 * @param settledThrough 이 행에 반영된 도착 가운데 가장 늦은 시각
 */
public record SameDayFullOutcomeCount(
    int stopsToTarget,
    int rowCount,
    int actualFullCount,
    double rawFullChanceSum,
    Instant settledThrough
) {
    public SameDayFullOutcomeCount {
        if (rowCount < 0 || hasValuesWithoutRows(stopsToTarget, rowCount, actualFullCount, rawFullChanceSum)) {
            throw new IllegalArgumentException("빈 집계 표시는 거리·건수·합계가 모두 0이어야 한다: " + rowCount);
        }
    }

    private static boolean hasValuesWithoutRows(
        int stopsToTarget,
        int rowCount,
        int actualFullCount,
        double rawFullChanceSum
    ) {
        if (rowCount != 0) {
            return false;
        }
        if (stopsToTarget != 0 || actualFullCount != 0) {
            return true;
        }
        return rawFullChanceSum != 0;
    }

    public SameDayFullOutcomes outcomes() {
        return new SameDayFullOutcomes(rowCount, actualFullCount, meanRawFullChance());
    }

    private double meanRawFullChance() {
        if (rowCount == 0) {
            return 0;
        }
        return rawFullChanceSum / rowCount;
    }
}
