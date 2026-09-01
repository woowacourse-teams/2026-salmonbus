package com.gustler.backend.processor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 한 노선 판본 · 한 시간대의 셀 통계 전부. 한 세대의 산출물이다.
 *
 * <p>셀 하나만 집어 오는 길을 두지 않는다. 셋이 이 안에서 닫혀야 하기 때문이다.
 *
 * <ul>
 *   <li>z화. 평균과 표준편차를 저장하지 않고 같은 세대의 행들에서 그때 유도한다
 *   <li>이웃 폴백. 자기 셀이 없으면 반경 4 안의 다른 셀이 손에 있어야 한다
 *   <li>구간합. 통과할 구간의 셀들을 합쳐 읽는다
 * </ul>
 *
 * <p>행은 승차 가능한 정류장 수만큼이다. 판본 정류장 수가 아니다. 그래서 순번이 건너뛴다.
 */
public record StopDemandStatistics(
    long routeVersionId,
    TimeSlot timeSlot,
    int revision,
    List<StopDemandCell> cells
) {

    /** 이웃을 찾는 순번 거리. 자기 자신은 뺀다. */
    private static final int NEIGHBOUR_RADIUS = 4;

    /** 표준편차가 0인 세대에서 0으로 나누지 않으려고 더한다. */
    private static final double STANDARD_DEVIATION_FLOOR = 1e-9;

    /** 이웃도 없을 때 쓰는 값. 원값 0이 아니라 그 세대 평균에 해당하는 z값이다. */
    private static final double NO_NEIGHBOUR_SCORE = 0.0;

    public StopDemandStatistics {
        cells = List.copyOf(cells);
        Map<Integer, StopDemandCell> byStopOrder = new LinkedHashMap<>();
        for (StopDemandCell cell : cells) {
            if (byStopOrder.put(cell.stopOrder(), cell) != null) {
                throw new IllegalArgumentException("한 정류장의 셀은 하나다: " + cell.stopOrder());
            }
        }
    }

    /** 그 정류장에 도착할 때 자리가 얼마나 차 있었나. 세대 안에서 z 로 잰다. */
    public double fillRateScoreAt(
        final int stopOrder
    ) {
        return scoreAt(stopOrder, StopDemandCell::averageFillRate);
    }

    /** 그 정류장에서 순승차가 얼마나 있었나. 세대 안에서 z 로 잰다. */
    public double netBoardingScoreAt(
        final int stopOrder
    ) {
        return scoreAt(stopOrder, StopDemandCell::averageNetBoardingRate);
    }

    /**
     * 그 정류장의 셀이 없어 이웃으로 메웠나.
     *
     * <p>모델이 읽는 것은 좌석 결측률이 아니라 이 값이다. 열로 두지 않는다.
     * 그 셀의 행이 있는지 없는지가 곧 지시자다.
     */
    public boolean isFilledByNeighboursAt(
        final int stopOrder
    ) {
        return cellAt(stopOrder) == null;
    }

    /**
     * 차량이 통과할 구간의 순승차를 합친 값.
     *
     * <p>창은 대상 순번에서 지평만큼 거슬러 올라간 자리부터 대상 순번까지다.
     * 나누는 것은 창 길이가 아니라 <b>그 창 안에 셀이 실제로 있던 정류장 수</b>다.
     * 경유 정류장에는 셀이 없어서 둘이 갈린다. 하나도 없으면 이웃 폴백으로 넘긴다.
     */
    public double netBoardingSegmentScoreOf(
        final int targetStopOrder,
        final int stopsToTarget
    ) {
        if (cells.isEmpty()) {
            return NO_NEIGHBOUR_SCORE;
        }
        final int windowFrom = targetStopOrder - stopsToTarget + 1;
        double total = 0;
        int seen = 0;
        for (int stopOrder = windowFrom; stopOrder <= targetStopOrder; stopOrder++) {
            StopDemandCell cell = cellAt(stopOrder);
            if (cell != null) {
                total += standardized(cell.averageNetBoardingRate(), StopDemandCell::averageNetBoardingRate);
                seen++;
            }
        }
        if (seen == 0) {
            return netBoardingScoreAt(targetStopOrder);
        }
        return total / Math.sqrt(seen);
    }

    private double scoreAt(
        final int stopOrder,
        RateOfCell rate
    ) {
        if (cells.isEmpty()) {
            return NO_NEIGHBOUR_SCORE;
        }
        StopDemandCell cell = cellAt(stopOrder);
        if (cell != null) {
            return standardized(rate.of(cell), rate);
        }
        return neighbourScoreAt(stopOrder, rate);
    }

    /** 반경 4 안의 이웃 z값을 거리 제곱에 반비례하게 섞는다. 이웃도 없으면 세대 평균 자리다. */
    private double neighbourScoreAt(
        final int stopOrder,
        RateOfCell rate
    ) {
        double weightedTotal = 0;
        double totalWeight = 0;
        for (StopDemandCell cell : cells) {
            final int distance = Math.abs(cell.stopOrder() - stopOrder);
            if (distance == 0 || distance > NEIGHBOUR_RADIUS) {
                continue;
            }
            final double weight = 1.0 / (distance * distance);
            weightedTotal += weight * standardized(rate.of(cell), rate);
            totalWeight += weight;
        }
        if (totalWeight == 0) {
            return NO_NEIGHBOUR_SCORE;
        }
        return weightedTotal / totalWeight;
    }

    private double standardized(
        final double value,
        RateOfCell rate
    ) {
        final double mean = meanOf(rate);
        return (value - mean) / (standardDeviationOf(rate, mean) + STANDARD_DEVIATION_FLOOR);
    }

    private double meanOf(
        RateOfCell rate
    ) {
        double total = 0;
        for (StopDemandCell cell : cells) {
            total += rate.of(cell);
        }
        return total / cells.size();
    }

    /** 모집단 표준편차다. 이 세대의 행이 곧 모집단이라 표본 보정을 하지 않는다. */
    private double standardDeviationOf(
        RateOfCell rate,
        final double mean
    ) {
        double squaredTotal = 0;
        for (StopDemandCell cell : cells) {
            final double gap = rate.of(cell) - mean;
            squaredTotal += gap * gap;
        }
        return Math.sqrt(squaredTotal / cells.size());
    }

    private StopDemandCell cellAt(
        final int stopOrder
    ) {
        for (StopDemandCell cell : cells) {
            if (cell.stopOrder() == stopOrder) {
                return cell;
            }
        }
        return null;
    }

    @FunctionalInterface
    private interface RateOfCell {

        double of(
            StopDemandCell cell
        );
    }
}
