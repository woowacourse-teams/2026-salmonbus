package com.gustler.backend.api.salmon.offline;

import com.gustler.backend.api.salmon.offline.PassOutcome.Seats;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 근사 확률이 붙은 차량 한 건(시도 또는 보드의 차량)과 실제 결과를 모아 센다. 결과는 첫 관측 기준과 마지막 관측 기준을 따로 낸다.
 */
final class OutcomeTally {
    private static final int BINS = 10;

    /** 0부터 1 사이 값을 열 구간으로 나눠, 구간마다 건수와 사건이 일어난 건수를 센다. */
    static final class Bins {
        private final int[] counts = new int[BINS];
        private final int[] events = new int[BINS];
        private final double[] sums = new double[BINS];

        void add(double value, boolean event) {
            int bin = Math.min(BINS - 1, (int) Math.floor(value * BINS));
            counts[bin]++;
            sums[bin] += value;
            if (event) {
                events[bin]++;
            }
        }

        List<Map<String, Object>> rows(String eventName) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int bin = 0; bin < BINS; bin++) {
                if (counts[bin] == 0) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("from", bin / (double) BINS);
                row.put("to", (bin + 1) / (double) BINS);
                row.put("count", counts[bin]);
                row.put("meanPredicted", sums[bin] / counts[bin]);
                row.put(eventName, events[bin]);
                row.put(eventName + "Rate", events[bin] / (double) counts[bin]);
                rows.add(row);
            }
            return rows;
        }
    }

    /** 앞차와 뒤차 두 대의 결과를 모두 알 때, 둘 다 만석 출발인 건수와 근사 확률의 곱으로 예측한 건수를 센다. */
    static final class Pairs {
        private int bothKnown;
        private int bothFull;
        private int firstFull;
        private int secondFull;
        private int predicted;
        private double predictedBothFull;
        private int bothFullAmongPredicted;

        void add(Optional<Double> firstChance, Seats first, Optional<Double> secondChance, Seats second) {
            if (!first.known() || !second.known()) {
                return;
            }
            bothKnown++;
            firstFull += first.full() ? 1 : 0;
            secondFull += second.full() ? 1 : 0;
            boolean both = first.full() && second.full();
            bothFull += both ? 1 : 0;
            if (firstChance.isPresent() && secondChance.isPresent()) {
                predicted++;
                predictedBothFull += (1 - firstChance.get()) * (1 - secondChance.get());
                bothFullAmongPredicted += both ? 1 : 0;
            }
        }

        Map<String, Object> counts() {
            Map<String, Object> counts = new LinkedHashMap<>();
            counts.put("pairsWithBothKnown", bothKnown);
            counts.put("firstFull", firstFull);
            counts.put("secondFull", secondFull);
            counts.put("bothFull", bothFull);
            counts.put("pairsWithBothChances", predicted);
            counts.put("bothFullAmongThose", bothFullAmongPredicted);
            counts.put("bothFullPredictedByProduct", predictedBothFull);
            return counts;
        }
    }

    private int units;
    private final Map<String, Integer> firstResults = new TreeMap<>();
    private final Map<String, Integer> lastResults = new TreeMap<>();
    private final Bins firstCalibration = new Bins();
    private final Bins lastCalibration = new Bins();
    private int bothKnown;
    private int sameSeats;
    private int firstFullLastOpen;
    private int firstOpenLastFull;
    private long seatDifference;

    /** @param chance 좌석 예보에서 온 탑승 확률 근사(1 - 만석 확률). 예보가 없으면 빈 값 */
    void add(Optional<Double> chance, PassOutcome outcome) {
        units++;
        firstResults.merge(outcome.first().label(), 1, Integer::sum);
        lastResults.merge(outcome.last().label(), 1, Integer::sum);
        chance.filter(value -> outcome.first().known())
            .ifPresent(value -> firstCalibration.add(value, !outcome.first().full()));
        chance.filter(value -> outcome.last().known())
            .ifPresent(value -> lastCalibration.add(value, !outcome.last().full()));
        if (outcome.first().known() && outcome.last().known()) {
            int first = outcome.first().seats().getAsInt();
            int last = outcome.last().seats().getAsInt();
            bothKnown++;
            sameSeats += first == last ? 1 : 0;
            firstFullLastOpen += first == 0 && last > 0 ? 1 : 0;
            firstOpenLastFull += first > 0 && last == 0 ? 1 : 0;
            seatDifference += Math.abs(first - last);
        }
    }

    Map<String, Object> counts() {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("units", units);
        counts.put("byFirstObservation", basis(firstResults, firstCalibration));
        counts.put("byLastObservation", basis(lastResults, lastCalibration));
        Map<String, Object> difference = new LinkedHashMap<>();
        difference.put("unitsWithBothKnown", bothKnown);
        difference.put("sameSeats", sameSeats);
        difference.put("firstFullButLastHasSeats", firstFullLastOpen);
        difference.put("firstHasSeatsButLastFull", firstOpenLastFull);
        difference.put("meanAbsoluteSeatDifference", bothKnown == 0 ? null : seatDifference / (double) bothKnown);
        counts.put("firstVersusLast", difference);
        return counts;
    }

    private static Map<String, Object> basis(Map<String, Integer> results, Bins calibration) {
        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("results", results);
        basis.put("seatsAvailableByChance", calibration.rows("seatsAvailable"));
        return basis;
    }

    /** 차량들의 결과에서 대기의 성공 여부를 정한다. 한 대라도 빈자리가 있으면 성공, 전부 만석이면 실패다. */
    static String stayOf(List<Seats> results, String whenEmpty) {
        if (results.isEmpty()) {
            return whenEmpty;
        }
        if (results.stream().anyMatch(seats -> seats.known() && !seats.full())) {
            return "SUCCESS";
        }
        return results.stream().allMatch(Seats::full) ? "FAIL" : "UNKNOWN";
    }
}
