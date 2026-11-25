package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.application.forecast.ScoringReasons;
import com.gustler.backend.forecasting.application.forecast.ScoringStage;
import com.gustler.backend.forecasting.support.Baseline;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@link ScoringScenario} 하나의 예측별 정산 결과의 고정 기대값이다({@link Baseline}). 값은 기준 구현(dev {@code e98ae3ea}의 테스트가
 * 기준으로 삼던, 전체 이력으로 정답을 내는 정산 계산)이 같은 흐름에 낸 것이다. 그 구현은 저장소에 남아 있지 않아 파일을 다시 만들 수
 * 없으므로, {@link ScoringScenario}가 관측, 예보를 만드는 순서와 난수를 뽑는 순서를 바꾸면 기대값과 견줄 수 없다.
 *
 * <p>기대값은 그 계산을 배치마다 한 번씩 돌려 얻었다. 예측 기준 시각부터 {@link ScoringStage#MATCH_WINDOW} 안의 평가에서 처음 나온
 * 결과가 그 예측의 결과이고 끝까지 없으면 놓침이다. 평가마다 예측 기준 시각의 한국 날짜 00시부터 평가 시각까지의 관측만 썼다. 정산
 * 단계는 날짜를 가리지 않고 통과가 닫힐 때 한 번 계산하므로, 아래 두 경우에는 기대값과 달라도 설명되는 차이로 센다.
 * <ul>
 *   <li>자정 범위: 자정을 넘겨 이어진 운행의 예측이 날짜 경계 없이 본 기대값({@code c} 줄)과 같다</li>
 *   <li>구멍 시점: 기대값의 사유가 관측 누락이나 관측 상한인데, 정산 단계는 그 전에 통과가 생길 수 없거나 조건이 맞지 않아 닫았다</li>
 * </ul>
 *
 * <p>줄 형식은 다음과 같다. 관측은 {@link ScoringScenario}가 매긴 번호다.
 * <ul>
 *   <li>{@code hash 번호 계수해시}: 결과 줄이 번호로 가리키는 계수 해시</li>
 *   <li>{@code k 번호 SETTLED 정답값 해시번호 정원 도착전관측 마지막관측 닫은관측 직전마지막관측 직전닫은관측}: 정산된 결과</li>
 *   <li>{@code k 번호 상태 사유}: 정산되지 않고 닫힌 결과</li>
 *   <li>{@code f 관측 목표:결과번호 …}: 관측 하나의 목표 정류장별 기대 결과</li>
 *   <li>{@code c 관측 목표:결과번호 …}: 자정을 넘겨 이어진 운행의 예측을 날짜 경계 없이 본 기대 결과. {@code f} 줄과 다른 것만 있다</li>
 * </ul>
 */
final class ScoringScenarioBaseline {

    private static final List<String> TAINTS = List.of(ScoringReasons.SOURCE_HISTORY_INCOMPLETE,
        ScoringReasons.OBSERVATION_INPUT_LIMIT);
    /** 정산 단계가 마감을 기다리지 않고 바로 닫는 사유. 기준 구현은 마감까지 보아서 그 사이에 생긴 관측 누락, 관측 상한이 기대값에만 있다. */
    private static final List<String> IMMEDIATE = List.of(ScoringReasons.TARGET_WITHOUT_PASS,
        ScoringReasons.PASS_CONDITION_UNMET);

    private final Map<String, String> hashes = new HashMap<>();
    private final Map<String, ScoringScenario.Verdict> results = new HashMap<>();
    private final Map<ScoringScenario.Key, ScoringScenario.Verdict> verdicts = new LinkedHashMap<>();
    private final Map<ScoringScenario.Key, ScoringScenario.Verdict> withoutDayBoundary = new HashMap<>();
    private boolean exactMath;

    private ScoringScenarioBaseline() {
    }

    static ScoringScenarioBaseline read(String name) {
        ScoringScenarioBaseline baseline = new ScoringScenarioBaseline();
        for (String[] line : Baseline.lines(name)) {
            baseline.accept(line);
        }
        return baseline;
    }

    private void accept(String[] line) {
        switch (line[0]) {
            case "math" -> exactMath = Baseline.sameMath(line[1]);
            case "hash" -> hashes.put(line[1], line[2]);
            case "k" -> results.put(line[1], result(line));
            case "f" -> putAll(verdicts, line);
            case "c" -> putAll(withoutDayBoundary, line);
            default -> throw new IllegalStateException("모르는 줄: " + line[0]);
        }
    }

    private ScoringScenario.Verdict result(String[] line) {
        if (!"SETTLED".equals(line[2])) {
            return ScoringScenario.Verdict.of(line[2], line[3]);
        }
        return new ScoringScenario.Verdict(
                line[2],
                null,
                Double.doubleToLongBits(Baseline.value(line[3])),
                hashes.get(line[4]),
                Integer.valueOf(line[5]),
                Long.valueOf(line[6]),
                Long.valueOf(line[7]),
                Long.valueOf(line[8]),
                Long.valueOf(line[9]),
                Long.valueOf(line[10])
        );
    }

    private void putAll(Map<ScoringScenario.Key, ScoringScenario.Verdict> target, String[] line) {
        final long observation = Long.parseLong(line[1]);
        for (int index = 2; index < line.length; index++) {
            String[] parts = line[index].split(":");
            ScoringScenario.Verdict result = results.get(parts[1]);
            if (result == null) {
                throw new IllegalStateException("모르는 결과 번호: " + parts[1]);
            }
            target.put(new ScoringScenario.Key(observation, Integer.parseInt(parts[0])), result);
        }
    }

    /**
     * 정산 결과를 예측마다 기대값과 견준다. 기대값과 다른 예측은 자정 범위나 구멍 시점으로 설명되지 않으면 설명되지 않는 차이로
     * 모으고, 기대값에 없는 예측도 설명되지 않는 차이다.
     */
    Comparison compare(ScoringScenario scenario, ScoringScenario.Scored scored) {
        Map<String, Integer> outcomes = new TreeMap<>();
        int equal = 0;
        int midnight = 0;
        int taint = 0;
        List<Difference> unexplained = new ArrayList<>();
        for (Map.Entry<ScoringScenario.Key, ScoringScenario.Verdict> entry : verdicts.entrySet()) {
            ScoringScenario.Key key = entry.getKey();
            ScoringScenario.Verdict expected = entry.getValue();
            outcomes.merge(expected.state() + ":" + expected.reason(), 1, Integer::sum);
            ScoringScenario.Verdict actual = scored.verdicts().get(key);
            if (same(expected, actual)) {
                equal++;
                continue;
            }
            String reason = scored.reasons().get(key);
            if (isMidnightScope(scenario, key, actual)) {
                midnight++;
                continue;
            }
            if (isTaintTiming(expected, reason)) {
                taint++;
                continue;
            }
            unexplained.add(new Difference(
                    key,
                    expected,
                    actual,
                    reason
            ));
        }
        addScoredOnly(scored, unexplained);
        return new Comparison(
                verdicts.size(),
                outcomes,
                equal,
                midnight,
                taint,
                unexplained
        );
    }

    /** 자정을 넘겨 이어진 운행의 예측이고 날짜 경계 없이 본 기대값과는 같은지 본다. */
    private boolean isMidnightScope(ScoringScenario scenario, ScoringScenario.Key key, ScoringScenario.Verdict actual) {
        ScoringScenario.Verdict continuous = withoutDayBoundary.get(key);
        return continuous != null && same(continuous, actual) && scenario.continuesFromPreviousDay(key);
    }

    private static boolean isTaintTiming(ScoringScenario.Verdict expected, String reason) {
        if (reason == null || expected.reason() == null) {
            return false;
        }
        return IMMEDIATE.contains(reason) && TAINTS.contains(expected.reason());
    }

    private void addScoredOnly(ScoringScenario.Scored scored, List<Difference> unexplained) {
        for (Map.Entry<ScoringScenario.Key, ScoringScenario.Verdict> entry : scored.verdicts().entrySet()) {
            if (!verdicts.containsKey(entry.getKey())) {
                unexplained.add(new Difference(
                        entry.getKey(),
                        null,
                        entry.getValue(),
                        scored.reasons().get(entry.getKey())
                ));
            }
        }
    }

    /** 함수 지문이 같은 JVM에서는 모든 열을 그대로 견주고, 다른 JVM에서는 정답 값만 상대 오차 안에서 견준다. */
    private boolean same(ScoringScenario.Verdict expected, ScoringScenario.Verdict actual) {
        if (expected.equals(actual)) {
            return true;
        }
        if (exactMath || !hasLabels(expected, actual)) {
            return false;
        }
        return Baseline.sameValue(Double.longBitsToDouble(expected.labelBits()),
            Double.longBitsToDouble(actual.labelBits()), false) && expected.equals(withLabel(actual, expected));
    }

    private static boolean hasLabels(ScoringScenario.Verdict expected, ScoringScenario.Verdict actual) {
        return actual != null && expected.labelBits() != null && actual.labelBits() != null;
    }

    private static ScoringScenario.Verdict withLabel(ScoringScenario.Verdict verdict, ScoringScenario.Verdict label) {
        return new ScoringScenario.Verdict(
                verdict.state(),
                verdict.reason(),
                label.labelBits(),
                verdict.hash(),
                verdict.capacity(),
                verdict.arrival(),
                verdict.last(),
                verdict.closure(),
                verdict.previousLast(),
                verdict.previousClosure()
        );
    }

    /**
     * @param key 예측
     * @param expected 기대 결과. 기대값에 없는 예측이면 비어 있다
     * @param scored 정산 결과. 정산 결과가 없으면 비어 있다
     * @param reason 정산 단계가 남긴 사유
     */
    record Difference(
        ScoringScenario.Key key,
        ScoringScenario.Verdict expected,
        ScoringScenario.Verdict scored,
        String reason
    ) {

    }

    /**
     * @param forecasts 기대값의 예측 수
     * @param expectedOutcomes 기대값의 {@code 상태:사유}별 예측 수
     * @param equal 기대값과 같은 예측 수
     * @param midnightScope 자정 범위로 설명되는 예측 수
     * @param taintTiming 구멍 시점으로 설명되는 예측 수
     * @param unexplained 설명되지 않는 차이
     */
    record Comparison(
        int forecasts,
        Map<String, Integer> expectedOutcomes,
        int equal,
        int midnightScope,
        int taintTiming,
        List<Difference> unexplained
    ) {

    }
}
