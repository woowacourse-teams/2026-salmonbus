package com.gustler.backend.forecasting.infrastructure.observability;

import com.gustler.backend.forecasting.api.forecast.ForecastClosed;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 정산을 마친 예측을 (노선, 노선 버전, 목표 정류장, 모델 배포, 예측 거리)마다 모아 정확도 합계를 낸다. 시계도 DB도 읽지 않으므로 같은 이벤트를
 * 같은 순서로 주면 같은 합계가 나온다.
 *
 * <p>정산을 마친 예측은 모두 완료 수에 센다. 오차에는 정산 완료로 닫혀 정답 값이 0 이상인 예측만 넣는다.
 * <ul>
 *   <li>좌석 오차: 기대 좌석이 유한하고 0 이상이면 기대 좌석과 정답 값(하차 뒤 좌석)의 차이를 더한다.</li>
 *   <li>만석 확률 오차: 만석 확률이 0 이상 1 이하면 확률과 실제의 차이를 제곱해 더한다. 실제는 통과의 마지막 관측 좌석이 0이면 1,
 *       아니면 0이다.</li>
 * </ul>
 * 예측 거리는 기준 관측에서 목표까지의 정류장 수를 {@code 1-3}, {@code 4-6}, {@code 7-12}로 나눈 것이다. 키가
 * {@value #MAXIMUM_KEYS}개 있으면 새 키의 예측은 세지 않고 버린 수에 더한다. 버린 수는 꺼내도 줄지 않는다.
 */
public final class ForecastAccuracy {

    static final int MAXIMUM_KEYS = 5_000;

    private final Map<Key, Sums> sums = new LinkedHashMap<>();
    private final AtomicLong dropped = new AtomicLong();

    public synchronized void add(List<ForecastClosed> closed) {
        for (ForecastClosed forecast : closed) {
            Key key = new Key(
                    forecast.routeId(),
                    forecast.routeVersionId(),
                    forecast.targetStopOrder(),
                    forecast.modelDeploymentId(),
                    distance(forecast.stopsToTarget())
            );
            if (!sums.containsKey(key) && sums.size() >= MAXIMUM_KEYS) {
                dropped.incrementAndGet();
                continue;
            }
            sums.computeIfAbsent(key, ignored -> new Sums()).add(forecast);
        }
    }

    /** 모은 합계를 꺼내고 비운다. 키는 처음 나온 순서다. */
    public synchronized Map<Key, Totals> drain() {
        Map<Key, Totals> drained = new LinkedHashMap<>();
        sums.forEach((key, value) -> drained.put(key, value.totals()));
        sums.clear();
        return drained;
    }

    /** 키 상한에 걸려 세지 못한 예측의 누적 수. */
    public long dropped() {
        return dropped.get();
    }

    private static String distance(final int stops) {
        if (stops <= 3) {
            return "1-3";
        }
        if (stops <= 6) {
            return "4-6";
        }
        return "7-12";
    }

    private static boolean isEligible(ForecastClosed forecast) {
        if (forecast.labelSeats() == null || forecast.departureSeats() == null) {
            return false;
        }
        return forecast.labelSeats() >= 0;
    }

    private static boolean hasValidExpectedSeats(final double expectedSeats) {
        return Double.isFinite(expectedSeats) && expectedSeats >= 0;
    }

    private static boolean hasValidFullProbability(final double fullProbability) {
        if (!Double.isFinite(fullProbability) || fullProbability < 0) {
            return false;
        }
        return fullProbability <= 1;
    }

    private static double actualFullOutcome(ForecastClosed forecast) {
        if (forecast.departureSeats() == 0) {
            return 1;
        }
        return 0;
    }

    /** 정확도를 집계할 기준 키다. */
    public record Key(
        long routeId,
        long routeVersionId,
        int stopOrder,
        long modelDeploymentId,
        String distance
    ) {

    }

    /**
     * 키 하나의 합계다.
     *
     * @param completed 정산을 마친 예측 수
     * @param maeCount 좌석 오차에 넣은 예측 수
     * @param absoluteErrorSum 좌석 오차의 합
     * @param probabilityCount 만석 확률 오차에 넣은 예측 수
     * @param brierSum 만석 확률 제곱 오차의 합
     * @param predictedFullSum 예측한 만석 확률의 합
     * @param actualFullCount 실제로 만석이던 예측 수
     */
    public record Totals(
        long completed,
        long maeCount,
        double absoluteErrorSum,
        long probabilityCount,
        double brierSum,
        double predictedFullSum,
        long actualFullCount
    ) {

    }

    private static final class Sums {

        private long completed;
        private long maeCount;
        private long probabilityCount;
        private long actualFullCount;
        private double absoluteErrorSum;
        private double brierSum;
        private double predictedFullSum;

        private void add(ForecastClosed forecast) {
            completed++;
            if (!isEligible(forecast)) {
                return;
            }
            if (hasValidExpectedSeats(forecast.expectedSeats())) {
                maeCount++;
                absoluteErrorSum += Math.abs(forecast.expectedSeats() - forecast.labelSeats());
            }
            if (hasValidFullProbability(forecast.fullProbability())) {
                final double actual = actualFullOutcome(forecast);
                probabilityCount++;
                brierSum += Math.pow(forecast.fullProbability() - actual, 2);
                predictedFullSum += forecast.fullProbability();
                actualFullCount += (long) actual;
            }
        }

        private Totals totals() {
            return new Totals(
                    completed,
                    maeCount,
                    absoluteErrorSum,
                    probabilityCount,
                    brierSum,
                    predictedFullSum,
                    actualFullCount
            );
        }
    }
}
