package com.gustler.backend.forecasting.application.pipeline;

import com.gustler.backend.forecasting.api.pipeline.ForecastPublished.VehicleForecast;
import com.gustler.backend.forecasting.domain.publication.SeatForecast;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 배치 하나에서 옛 경로의 예측과 새 경로의 예측을 (관측, 목표 정류장)마다 대조한 결과다.
 *
 * <p>기대 좌석과 만석 확률을 {@link Double#doubleToLongBits}로 비교한다. 새 경로가 되감은 직후라 운행 시작이나 창 밖 영향을 몰라
 * 뺀 관측({@code TRIP_START_UNKNOWN}, {@code REPLAY_HISTORY_INCOMPLETE})의 옛 예측은 "대조 제외"로 따로 센다.
 * 값이 다른 예측은 앞의 {@value #KEPT_DIFFERENCES}건만 담는다.
 */
public final class ShadowForecastComparison {
    static final int KEPT_DIFFERENCES = 5;

    private final Map<Result, Integer> counts;
    private final List<Difference> differences;

    private ShadowForecastComparison(Map<Result, Integer> counts, List<Difference> differences) {
        this.counts = counts;
        this.differences = List.copyOf(differences);
    }

    /**
     * 두 경로의 예측을 대조한다.
     *
     * @param legacy 옛 경로가 커밋한 예측
     * @param fresh 새 경로가 낸 버스별 예측
     * @param excludedObservations 새 경로가 되감기 때문에 예측에서 뺀 관측
     * @return 대조 결과
     */
    public static ShadowForecastComparison compare(
        List<SeatForecast> legacy,
        List<VehicleForecast> fresh,
        Set<Long> excludedObservations
    ) {
        Map<Key, double[]> news = new LinkedHashMap<>();
        for (VehicleForecast vehicle : fresh) {
            int[] targets = vehicle.targetStopOrders();
            double[] seats = vehicle.expectedSeats();
            double[] probabilities = vehicle.fullProbabilities();
            for (int index = 0; index < targets.length; index++) {
                news.put(new Key(vehicle.vehicleObservationId(), targets[index]),
                    new double[] {seats[index], probabilities[index]});
            }
        }
        Map<Result, Integer> counts = new EnumMap<>(Result.class);
        List<Difference> differences = new ArrayList<>();
        for (SeatForecast old : legacy) {
            Key key = new Key(old.vehicleObservationId(), old.targetStopOrder());
            double[] candidate = news.remove(key);
            if (candidate == null) {
                counts.merge(excludedObservations.contains(key.observationId()) ? Result.EXCLUDED : Result.LEGACY_ONLY, 1,
                    Integer::sum);
                continue;
            }
            final double oldSeats = old.expectedSeats() == null ? Double.NaN : old.expectedSeats();
            if (sameBits(oldSeats, candidate[0]) && sameBits(old.seatFullChance(), candidate[1])) {
                counts.merge(Result.SAME, 1, Integer::sum);
                continue;
            }
            counts.merge(Result.VALUE_DIFFERS, 1, Integer::sum);
            if (differences.size() < KEPT_DIFFERENCES) {
                differences.add(new Difference(key.observationId(), key.target(), oldSeats, candidate[0],
                    old.seatFullChance(), candidate[1]));
            }
        }
        if (!news.isEmpty()) {
            counts.merge(Result.NEW_ONLY, news.size(), Integer::sum);
        }
        return new ShadowForecastComparison(counts, differences);
    }

    private static boolean sameBits(final double left, final double right) {
        return Double.doubleToLongBits(left) == Double.doubleToLongBits(right);
    }

    /** 분류별 예측 수. 없는 분류는 0이다. */
    public int count(Result result) {
        return counts.getOrDefault(result, 0);
    }

    /** 값이 다르거나 한쪽에만 있는 예측 수. 대조 제외는 세지 않는다. */
    public int mismatches() {
        return count(Result.VALUE_DIFFERS) + count(Result.LEGACY_ONLY) + count(Result.NEW_ONLY);
    }

    /** 값이 다른 예측의 앞 몇 건. */
    public List<Difference> differences() {
        return differences;
    }

    /** 예측 하나의 대조 분류다. 지표 태그 값은 {@link #tag()}다. */
    public enum Result {
        SAME("same"),
        VALUE_DIFFERS("value_differs"),
        LEGACY_ONLY("legacy_only"),
        NEW_ONLY("new_only"),
        EXCLUDED("excluded");

        private final String tag;

        Result(String tag) {
            this.tag = tag;
        }

        /** 지표 {@code result} 태그 값. */
        public String tag() {
            return tag;
        }
    }

    /** 값이 다른 예측 하나다. */
    public record Difference(
        long observationId,
        int target,
        double legacySeats,
        double newSeats,
        double legacyProbability,
        double newProbability
    ) {
    }

    private record Key(long observationId, int target) {
    }
}
