package com.gustler.backend.forecasting.infrastructure.pipeline;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 한 흐름의 예측값, 예보 실행 결과, 닫힌 통과, 입력 확정 시각의 고정 기대값이다({@link Baseline}).
 * <ul>
 *   <li>{@code run 배치 결과 예측수}: 배치의 예보 실행 결과와 예측 수. 예측을 내지 않는 배치는 예측 수가 {@code -}다</li>
 *   <li>{@code obs 관측 목표:기대좌석:만석확률 …}: 관측 하나의 목표 정류장별 예측값</li>
 *   <li>{@code pass 운행시작관측 정류장 닫은관측}: 닫힌 통과</li>
 *   <li>{@code confirmed 배치 시각}: 배치에 남은 입력 확정 시각</li>
 * </ul>
 */
final class ForecastValueBaseline {
    private final Map<String, Map<Integer, double[]>> predictions = new HashMap<>();
    private final Map<Integer, Run> runs = new TreeMap<>();
    private final Map<Integer, Instant> confirmed = new TreeMap<>();
    private final Set<String> passes = new HashSet<>();
    private boolean exactMath;

    private ForecastValueBaseline() {
    }

    static ForecastValueBaseline read(String name) {
        ForecastValueBaseline baseline = new ForecastValueBaseline();
        for (String[] line : Baseline.lines(name)) {
            baseline.accept(line);
        }
        return baseline;
    }

    private void accept(String[] line) {
        switch (line[0]) {
            case "math" -> exactMath = Baseline.sameMath(line[1]);
            case "run" -> runs.put(Integer.parseInt(line[1]), new Run(line[2], countOrNone(line[3])));
            case "obs" -> predictions.put(line[1], targets(line));
            case "pass" -> passes.add(line[1] + " " + line[2] + " " + line[3]);
            case "confirmed" -> confirmed.put(Integer.parseInt(line[1]), Instant.parse(line[2]));
            default -> throw new IllegalStateException("모르는 줄: " + line[0]);
        }
    }

    private static Integer countOrNone(String count) {
        if ("-".equals(count)) {
            return null;
        }
        return Integer.valueOf(count);
    }

    private static Map<Integer, double[]> targets(String[] line) {
        Map<Integer, double[]> targets = new HashMap<>();
        for (int index = 2; index < line.length; index++) {
            String[] parts = line[index].split(":");
            targets.put(Integer.valueOf(parts[0]), new double[] {Baseline.value(parts[1]), Baseline.value(parts[2])});
        }
        return targets;
    }

    Map<Integer, Run> runs() {
        return runs;
    }

    /** 예측을 내는 배치의 예측 수. */
    Map<Integer, Integer> predictionCounts() {
        return runs.entrySet().stream().filter(run -> run.getValue().predictionCount() != null)
            .collect(Collectors.toMap(Map.Entry::getKey, run -> run.getValue().predictionCount()));
    }

    Map<Integer, Instant> confirmed() {
        return confirmed;
    }

    /** {@code 운행시작관측 정류장 닫은관측} 모양의 닫힌 통과. */
    Set<String> passes() {
        return passes;
    }

    /**
     * 그 배치들의 저장된 예측({@code forecast_record})을 기대값과 견준다.
     *
     * @param batches 견줄 배치 번호
     * @return 같은 예측 수, 값이 다른 예측 수, 기대값에 없는 예측 수, 기대값에만 있는 예측의 기준 관측 번호
     */
    Comparison compare(JdbcClient jdbc, StreamKeys keys, Collection<Long> batches) {
        Set<Integer> ordinals = batches.stream().map(keys::batch).collect(Collectors.toSet());
        Set<String> unseen = new HashSet<>();
        predictions.forEach((observation, targets) -> {
            if (ordinals.contains(Integer.valueOf(observation.substring(0, observation.indexOf('.'))))) {
                targets.keySet().forEach(target -> unseen.add(observation + ":" + target));
            }
        });
        int[] counts = new int[3];
        jdbc.sql("""
            SELECT r.vehicle_observation_id,t.target,t.seats,t.probability FROM forecast_record r,
              unnest(r.target_stop_orders,r.expected_seats,r.full_probabilities) AS t(target,seats,probability)
            WHERE r.observation_batch_id IN (:batches)
            """).param("batches", batches).query(row -> {
                String observation = keys.observation(row.getLong(1));
                double[] expected = predictions.getOrDefault(observation, Map.of()).get(row.getInt(2));
                unseen.remove(observation + ":" + row.getInt(2));
                counts[kind(expected, row.getDouble(3), row.getDouble(4))]++;
            });
        Set<Long> baselineOnly = unseen.stream()
            .map(key -> keys.observationId(key.substring(0, key.indexOf(':'))))
            .collect(Collectors.toCollection(HashSet::new));
        return new Comparison(counts[0], counts[1], counts[2], baselineOnly);
    }

    private int kind(double[] expected, final double seats, final double probability) {
        if (expected == null) {
            return 2;
        }
        if (!Baseline.sameValue(expected[0], seats, exactMath)) {
            return 1;
        }
        if (!Baseline.sameValue(expected[1], probability, exactMath)) {
            return 1;
        }
        return 0;
    }

    /**
     * @param outcome 배치의 예보 실행 결과
     * @param predictionCount 예측 수. 예측을 내지 않는 배치는 비어 있다
     */
    record Run(String outcome, Integer predictionCount) {
    }

    /**
     * @param same 기대값과 같은 예측 수
     * @param valueDiffers 값이 다른 예측 수
     * @param newOnly 기대값에 없는 예측 수
     * @param baselineOnly 기대값에만 있는 예측의 기준 관측 번호
     */
    record Comparison(
        int same,
        int valueDiffers,
        int newOnly,
        Set<Long> baselineOnly
    ) {
    }
}
