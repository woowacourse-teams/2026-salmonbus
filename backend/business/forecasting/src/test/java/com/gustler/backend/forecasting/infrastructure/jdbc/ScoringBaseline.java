package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.support.Baseline;
import com.gustler.backend.forecasting.support.BaselineReasons;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 한 흐름의 예측별 정산 결과의 고정 기대값이다({@link Baseline}).
 * <ul>
 *   <li>{@code hash 번호 계수해시}: 정산 완료 줄이 번호로 가리키는 계수 해시</li>
 *   <li>{@code v 관측 목표 SETTLED 정답값 해시번호 정원 도착전관측 마지막관측 닫은관측 직전마지막관측 직전닫은관측}: 정산된 예측</li>
 *   <li>{@code v 관측 목표 상태 사유}: 미해결로 종료한 예측. 사유는 {@link BaselineReasons#recordedName}의 이름이다</li>
 * </ul>
 */
final class ScoringBaseline {

    private static final String READ_OUTCOMES = """
        SELECT vehicle_observation_id, target_stop_order, state, reason, label_value, parameter_hash, capacity,
            arrival_observation_id, last_observation_id, closure_observation_id, previous_last_observation_id,
            previous_closure_observation_id
        FROM forecast_outcome WHERE route_id = ?
        """;

    private final Map<String, Verdict> verdicts = new HashMap<>();
    private final Map<String, String> hashes = new HashMap<>();
    private boolean exactMath;

    private ScoringBaseline() {
    }

    static ScoringBaseline read(String name) {
        ScoringBaseline baseline = new ScoringBaseline();
        for (String[] line : Baseline.lines(name)) {
            baseline.accept(line);
        }
        return baseline;
    }

    private void accept(String[] line) {
        switch (line[0]) {
            case "math" -> exactMath = Baseline.sameMath(line[1]);
            case "hash" -> hashes.put(line[1], line[2]);
            case "v" -> verdicts.put(line[1] + " " + line[2], verdict(line));
            default -> throw new IllegalStateException("모르는 줄: " + line[0]);
        }
    }

    private Verdict verdict(String[] line) {
        if (!"SETTLED".equals(line[3])) {
            return Verdict.of(line[3], line[4]);
        }
        return new Verdict(
                line[3],
                null,
                Baseline.value(line[4]),
                hashes.get(line[5]),
                Integer.valueOf(line[6]),
                line[7],
                line[8],
                line[9],
                line[10],
                line[11]
        );
    }

    /**
     * 그 노선의 예측별 정산 결과({@code forecast_outcome})를 기대값과 견준다. 상태, 사유, 정산을 완료하면 정답 값, 계수 해시, 정원,
     * 도착 전, 마지막, 닫은 관측, 직전 통과의 마지막, 닫은 관측을 본다.
     */
    Comparison compare(JdbcClient jdbc, StreamKeys keys, final long routeId) {
        Set<String> unseen = new HashSet<>(verdicts.keySet());
        List<String> differences = new ArrayList<>();
        List<String> newOnly = new ArrayList<>();
        int[] counts = new int[2];
        jdbc.sql(READ_OUTCOMES).param(routeId).query(row -> {
            String key = keys.observation(row.getLong(1)) + " " + row.getInt(2);
            Verdict actual = actual(row, keys);
            Verdict expected = verdicts.get(key);
            unseen.remove(key);
            if ("PENDING".equals(actual.state())) {
                counts[1]++;
                return;
            }
            if (expected == null) {
                newOnly.add(key + " " + actual);
                return;
            }
            if (!expected.matches(actual, exactMath)) {
                differences.add(key + " 기대=" + expected + " 실제=" + actual);
                return;
            }
            counts[0]++;
        });
        return new Comparison(
                counts[0],
                differences,
                List.copyOf(unseen),
                newOnly,
                counts[1]
        );
    }

    private static Verdict actual(ResultSet row, StreamKeys keys) throws SQLException {
        String state = row.getString(3);
        String reason = row.getString(4);
        if ("SETTLED".equals(state)) {
            return new Verdict(
                    state,
                    null,
                    row.getDouble(5),
                    row.getString(6),
                    row.getInt(7),
                    keys.observationOrNone((Long) row.getObject(8)),
                    keys.observationOrNone((Long) row.getObject(9)),
                    keys.observationOrNone((Long) row.getObject(10)),
                    keys.observationOrNone((Long) row.getObject(11)),
                    keys.observationOrNone((Long) row.getObject(12))
            );
        }
        if ("PENDING".equals(state)) {
            return Verdict.of(state, null);
        }
        return Verdict.of(state, BaselineReasons.recordedName(reason).orElse("NONE:" + reason));
    }

    /** 견주는 열. 정산 완료가 아니면 상태와 사유만 채운다. 관측은 흐름의 이름이고 없으면 {@code -}다. */
    record Verdict(
        String state,
        String reason,
        Double label,
        String hash,
        Integer capacity,
        String arrival,
        String last,
        String closure,
        String previousLast,
        String previousClosure
    ) {

        static Verdict of(String state, String reason) {
            return new Verdict(
                    state,
                    reason,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );
        }

        boolean matches(Verdict other, final boolean exactMath) {
            if (!sameLabel(other, exactMath)) {
                return false;
            }
            return state.equals(other.state) && Objects.equals(reason, other.reason) && Objects.equals(hash, other.hash)
                && Objects.equals(capacity, other.capacity) && Objects.equals(arrival, other.arrival)
                && Objects.equals(last, other.last) && Objects.equals(closure, other.closure)
                && Objects.equals(previousLast, other.previousLast)
                && Objects.equals(previousClosure, other.previousClosure);
        }

        private boolean sameLabel(Verdict other, final boolean exactMath) {
            if (label == null || other.label == null) {
                return label == null && other.label == null;
            }
            return Baseline.sameValue(label, other.label, exactMath);
        }
    }

    /**
     * @param same 기대값과 같은 예측 수
     * @param differences 기대값과 다른 예측
     * @param baselineOnly 기대값에만 있는 예측
     * @param newOnly 기대값에 없는 예측
     * @param pending 아직 정산 전인 예측 수
     */
    record Comparison(
        int same,
        List<String> differences,
        List<String> baselineOnly,
        List<String> newOnly,
        int pending
    ) {

    }
}
