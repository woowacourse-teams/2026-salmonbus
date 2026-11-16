package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.support.Baseline;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 한 흐름의 배치별 예보 입력·노선 상태·닫힌 통과의 고정 기대값이다({@link Baseline}). 값은 {@link ForecastInputDigests}의 지문이다.
 * <ul>
 *   <li>{@code u 배치 사유}: 확정 조건을 만족하지 않아 입력을 만들지 않은 배치</li>
 *   <li>{@code f 배치 사유}: 입력을 만들지 못한 배치</li>
 *   <li>{@code o 배치}: 출퇴근 범위 밖이라 입력을 만들지 않은 배치</li>
 *   <li>{@code b 배치 순위 상태지문 통과수 통과지문}: 입력을 만든 배치. 상태를 견주지 않는 흐름은 뒤 네 칸이 {@code -}다</li>
 *   <li>{@code s 관측 입력지문}: 바로 앞 {@code b} 줄의 배치에서 예보한 차량 하나</li>
 * </ul>
 */
final class ForecastInputBaseline {
    private final Map<Integer, Batch> batches = new HashMap<>();

    private ForecastInputBaseline() {
    }

    static ForecastInputBaseline read(String name) {
        ForecastInputBaseline baseline = new ForecastInputBaseline();
        Batch current = null;
        for (String[] line : Baseline.lines(name)) {
            if ("s".equals(line[0])) {
                current.snapshots().put(line[1], line[2]);
                continue;
            }
            current = batch(line);
            baseline.batches.put(Integer.parseInt(line[1]), current);
        }
        return baseline;
    }

    private static Batch batch(String[] line) {
        return switch (line[0]) {
            case "u" -> new Batch(Kind.UNCONFIRMED, line[2], null, null, null, null, new LinkedHashMap<>());
            case "f" -> new Batch(Kind.FAILED, line[2], null, null, null, null, new LinkedHashMap<>());
            case "o" -> new Batch(Kind.OUT_OF_SCOPE, null, null, null, null, null, new LinkedHashMap<>());
            case "b" -> new Batch(Kind.FORECAST, null, longOrNone(line[2]), textOrNone(line[3]), intOrNone(line[4]),
                textOrNone(line[5]), new LinkedHashMap<>());
            default -> throw new IllegalStateException("모르는 줄: " + line[0]);
        };
    }

    private static String textOrNone(String text) {
        if ("-".equals(text)) {
            return null;
        }
        return text;
    }

    private static Long longOrNone(String text) {
        if ("-".equals(text)) {
            return null;
        }
        return Long.valueOf(text);
    }

    private static Integer intOrNone(String text) {
        if ("-".equals(text)) {
            return null;
        }
        return Integer.valueOf(text);
    }

    /** 그 순번의 배치. 기대값에 없으면 비어 있다. */
    Batch batch(final int ordinal) {
        return batches.get(ordinal);
    }

    enum Kind {
        UNCONFIRMED, FAILED, OUT_OF_SCOPE, FORECAST
    }

    /**
     * @param reason 입력을 만들지 않은 사유
     * @param rank 이 배치까지 반영한 상태의 순위
     * @param stateDigest 이 배치까지 반영한 상태의 지문
     * @param passCount 이 배치가 닫은 통과 수
     * @param passDigest 이 배치가 닫은 통과의 지문
     * @param snapshots 예보한 차량의 관측 이름별 입력 지문
     */
    record Batch(
        Kind kind,
        String reason,
        Long rank,
        String stateDigest,
        Integer passCount,
        String passDigest,
        Map<String, String> snapshots
    ) {
    }
}
