package com.gustler.backend.forecasting.infrastructure.pipeline;

import static com.gustler.backend.forecasting.infrastructure.pipeline.MultiRowInsert.instant;
import static com.gustler.backend.forecasting.infrastructure.pipeline.MultiRowInsert.nullableLong;

import com.gustler.backend.forecasting.api.pipeline.CompareShadowScoring;
import com.gustler.backend.forecasting.application.pipeline.ScoringReasons;
import com.gustler.backend.forecasting.domain.pipeline.SourceDates;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 새 채점 결과(뷰 {@code forecast_outcome})와 옛 정산 결과({@code alighting_forecast_evaluation_result})를 예측(관측, 목표
 * 정류장)마다 맞춰 보는 대조 작업이다. 읽기만 하며 행 잠금을 잡지 않는다.
 *
 * <p>읽는 범위는 예보 실행({@code forecast_run})을 배치 번호 순으로 따라가는 커서로 정한다. 한 회차에 배치를 최대
 * {@code batchLimit}개, 한 쿼리에 {@value #CHUNK}개씩 읽고, 수신 시각이 {@code now - delay}보다 늦은 배치를 만나면 거기서 멈춘다(옛 정산은 예측 기준 시각
 * 약 2시간 뒤에 결과를 적는다). 커서는 메모리에만 있고, 처음에는 만든 시각에서 {@code lookback}만큼 앞의 배치부터 시작한다.
 * 다시 뜨면 그 범위를 다시 대조하므로 지표가 겹쳐 셀 수 있다. 읽는 쿼리는 모두 기본 키나 인덱스의 앞 열로 찾는다.
 * <ul>
 *   <li>예보 실행: {@code (source_date, observation_batch_id)} 기본 키 범위</li>
 *   <li>새 결과: 뷰를 {@code forecast_record}의 배치 인덱스로, 짝은 {@code stop_pass}의 연결 인덱스와 각 테이블의 기본 키로</li>
 *   <li>옛 결과·옛 대기: 배치의 관측({@code vehicle_observation}의 배치 인덱스)에서 결과·대기의 기본 키(관측 번호가 앞 열)로</li>
 * </ul>
 *
 * <p>견주는 열은 상태, 사유({@link ScoringReasons#legacyReason}로 옛 이름에 맞춘 뒤), 그리고 채점됨이면 정답 값(비트), 계수
 * 해시, 정원, 도착 전·마지막·닫은 관측, 직전 통과의 마지막·닫은 관측이다. 분류는 다음과 같다.
 * <ul>
 *   <li>{@code same}: 모두 같다</li>
 *   <li>{@code intended_hole}: 옛 결과만 수집 구멍·관측 상한 사유다. 새 채점은 통과가 닫힌 시각까지만 보고 옛 정산은 평가
 *       시각까지 본다. 다시 뜬 뒤의 새 채점은 그 앞의 구멍을 모른다</li>
 *   <li>{@code intended_midnight}: 예측 기준 시각이 한국 시각 00시~03시다. 옛 정산은 날짜마다 상태를 비우고 다시 시작해
 *       자정 전부터 이어진 연결의 통과·직전 통과가 새 채점과 다를 수 있다</li>
 *   <li>{@code unexplained}: 위로 설명되지 않는 차이. 앞의 몇 건을 경고 로그로 남긴다</li>
 *   <li>{@code legacy_only}, {@code new_only}: 한쪽에만 결과가 있다. 새 예보가 되감은 직후 운행 시작을 모르는 버스를 빼면
 *       옛 결과만 남는다</li>
 *   <li>{@code pending}: 새 결과가 아직 채점 전이거나 옛 정산이 아직 대기 중이다. 커서가 지나가므로 다시 대조하지 않는다.
 *       지연({@code delay})이 옛 정산의 마감(2시간)과 밀림보다 길면 0이어야 한다</li>
 * </ul>
 * 결과는 {@code salmonbus.pipeline.scoring.compared{result}}로 센다. 한 스레드에서만 부른다.
 */
public final class JdbcScoringComparison implements CompareShadowScoring {
    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcScoringComparison.class);
    private static final String LEGACY_KIND = "AFTER_ALIGHTING_ESTIMATE";
    private static final Set<String> TAINTS = Set.of(ScoringReasons.SOURCE_HISTORY_INCOMPLETE,
        ScoringReasons.OBSERVATION_INPUT_LIMIT);
    private static final int MIDNIGHT_HOURS = 3;
    private static final int WARNINGS_PER_RUN = 5;
    private static final int CHUNK = 200;
    private static final String READ_RUNS = """
        SELECT /* salmonbus:scoring_comparison.runs */ source_date, observation_batch_id, received_at
        FROM forecast_run
        WHERE source_date >= :fromDate AND observation_batch_id > :cursor
        ORDER BY observation_batch_id
        LIMIT :limit
        """;
    private static final String READ_FRESH = """
        SELECT /* salmonbus:scoring_comparison.fresh */ vehicle_observation_id, target_stop_order, confirmed_at, state,
            reason, label_value, parameter_hash, capacity, arrival_observation_id, last_observation_id,
            closure_observation_id, previous_last_observation_id, previous_closure_observation_id
        FROM forecast_outcome
        WHERE source_date IN (:dates) AND observation_batch_id IN (:batches)
        """;
    private static final String READ_LEGACY = """
        SELECT /* salmonbus:scoring_comparison.legacy */ DISTINCT ON (r.vehicle_observation_id, r.target_stop_order)
            r.vehicle_observation_id, r.target_stop_order, r.scoring_state, r.reason, r.label_value, r.parameter_hash,
            r.capacity, r.pre_observation_id, r.post_observation_id, r.closure_observation_id,
            r.previous_post_observation_id, r.previous_closure_observation_id
        FROM vehicle_observation o
        JOIN alighting_forecast_evaluation_result r ON r.vehicle_observation_id = o.id
        WHERE o.observation_batch_id IN (:batches) AND r.target_kind = :kind AND r.label_version = :label
        ORDER BY r.vehicle_observation_id, r.target_stop_order, r.quality_revision DESC
        """;
    private static final String READ_LEGACY_PENDING = """
        SELECT /* salmonbus:scoring_comparison.legacy_pending */ w.vehicle_observation_id, w.target_stop_order
        FROM vehicle_observation o
        JOIN alighting_forecast_evaluation_work w ON w.vehicle_observation_id = o.id
        WHERE o.observation_batch_id IN (:batches) AND w.target_kind = :kind AND w.label_version = :label
        """;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final Duration delay;
    private final int batchLimit;
    private final MeterRegistry meters;
    private final Instant start;
    private long cursor = -1;

    /**
     * @param jdbc DB 접근
     * @param clock 지금 시각
     * @param delay 수신 뒤 이만큼 지난 배치만 대조한다. 옛 정산이 결과를 다 적을 시간이다
     * @param lookback 처음 대조를 시작할 배치의 수신 시각을 만든 시각에서 얼마나 앞으로 잡을지
     * @param batchLimit 한 회차에 읽는 예보 실행 수의 상한. 1 이상이다
     * @param meters 지표 등록기. 없으면 지표를 내지 않는다
     */
    public JdbcScoringComparison(
        JdbcClient jdbc,
        Clock clock,
        Duration delay,
        Duration lookback,
        final int batchLimit,
        MeterRegistry meters
    ) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.clock = Objects.requireNonNull(clock);
        if (hasMissingOrNegativeWindow(delay, lookback)) {
            throw new IllegalArgumentException("대조 지연과 시작 범위는 0 이상이어야 한다");
        }
        if (batchLimit < 1) {
            throw new IllegalArgumentException("한 번에 읽는 배치 수는 1 이상이어야 한다: " + batchLimit);
        }
        this.delay = delay;
        this.batchLimit = batchLimit;
        this.meters = meters;
        this.start = clock.instant().minus(lookback);
    }

    private static boolean hasMissingOrNegativeWindow(Duration delay, Duration lookback) {
        return delay == null || delay.isNegative() || lookback == null || lookback.isNegative();
    }

    @Override
    public void compareRecent() {
        compare();
    }

    /**
     * 커서 뒤의 배치를 대조하고 커서를 옮긴다.
     *
     * @return 이번 회차의 분류별 수와 설명되지 않는 차이
     */
    public Report compare() {
        Instant through = clock.instant().minus(delay);
        Report report = Report.empty();
        int remaining = batchLimit;
        while (remaining > 0) {
            final int chunk = Math.min(CHUNK, remaining);
            List<Run> runs = readRuns(through, chunk);
            Selection selection = select(runs, through);
            report = compareAndAdvance(report, runs, selection);
            remaining -= chunk;
            if (selection.reachedThrough() || runs.size() < chunk) {
                break;
            }
        }
        record(report);
        return report;
    }

    private List<Run> readRuns(Instant through, final int chunk) {
        return jdbc.sql(READ_RUNS)
            .param("fromDate", runsFromDate(through))
            .param("cursor", cursor)
            .param("limit", chunk)
            .query((row, index) -> new Run(row.getObject("source_date", LocalDate.class),
                row.getLong("observation_batch_id"), instant(row, "received_at")))
            .list();
    }

    /** 처음 회차는 시작 시각의 하루 전, 그 뒤로는 대조 상한 시각의 이틀 전 날짜부터 읽는다. */
    private LocalDate runsFromDate(Instant through) {
        if (cursor < 0) {
            return SourceDates.of(start).minusDays(1);
        }
        return SourceDates.of(through).minusDays(2);
    }

    /** 대조 상한보다 늦은 배치를 만나면 멈추고, 처음 회차에는 시작 시각보다 이른 배치를 건너뛴다. */
    private Selection select(List<Run> runs, Instant through) {
        List<Run> selected = new ArrayList<>();
        for (Run run : runs) {
            if (run.receivedAt().isAfter(through)) {
                return new Selection(selected, true);
            }
            if (cursor < 0 && run.receivedAt().isBefore(start)) {
                continue;
            }
            selected.add(run);
        }
        return new Selection(selected, false);
    }

    /** 고른 배치가 있으면 대조하고 커서를 마지막 고른 배치로, 없으면 상한에 닿지 않았을 때만 읽은 배치 끝으로 옮긴다. */
    private Report compareAndAdvance(Report report, List<Run> runs, Selection selection) {
        if (!selection.runs().isEmpty()) {
            Report compared = report.plus(compare(selection.runs()));
            cursor = selection.runs().getLast().observationBatchId();
            return compared;
        }
        if (!selection.reachedThrough() && !runs.isEmpty()) {
            cursor = runs.getLast().observationBatchId();
        }
        return report;
    }

    private Report compare(List<Run> runs) {
        List<Long> batches = runs.stream().map(Run::observationBatchId).toList();
        Set<LocalDate> dates = runs.stream()
            .map(Run::sourceDate)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Key, Fresh> fresh = readFresh(dates, batches);
        Map<Key, Verdict> legacy = readLegacy(batches);
        Set<Key> legacyPending = readLegacyPending(batches);
        Map<Result, Long> counts = new TreeMap<>();
        List<Difference> unexplained = new ArrayList<>();
        for (Fresh value : fresh.values()) {
            Verdict expected = legacy.get(value.key());
            Result result = classify(value, expected, legacyPending.contains(value.key()));
            counts.merge(result, 1L, Long::sum);
            if (result == Result.UNEXPLAINED) {
                unexplained.add(new Difference(value.key(), expected, value.verdict(), value.reason()));
            }
        }
        for (Map.Entry<Key, Verdict> entry : legacy.entrySet()) {
            if (!fresh.containsKey(entry.getKey())) {
                counts.merge(Result.LEGACY_ONLY, 1L, Long::sum);
            }
        }
        return new Report(runs.size(), counts, unexplained);
    }

    private Map<Key, Fresh> readFresh(Set<LocalDate> dates, List<Long> batches) {
        Map<Key, Fresh> fresh = new LinkedHashMap<>();
        jdbc.sql(READ_FRESH).param("dates", List.copyOf(dates)).param("batches", batches)
            .query(row -> {
                Fresh value = fresh(row);
                fresh.put(value.key(), value);
            });
        return fresh;
    }

    private Map<Key, Verdict> readLegacy(List<Long> batches) {
        Map<Key, Verdict> legacy = new LinkedHashMap<>();
        jdbc.sql(READ_LEGACY).param("batches", batches).param("kind", LEGACY_KIND)
            .param("label", ScoringReasons.LABEL_VERSION)
            .query(row -> {
                legacy.put(new Key(row.getLong("vehicle_observation_id"), row.getInt("target_stop_order")),
                    legacyVerdict(row));
            });
        return legacy;
    }

    private Set<Key> readLegacyPending(List<Long> batches) {
        Set<Key> legacyPending = new LinkedHashSet<>();
        jdbc.sql(READ_LEGACY_PENDING).param("batches", batches).param("kind", LEGACY_KIND)
            .param("label", ScoringReasons.LABEL_VERSION)
            .query(row -> {
                legacyPending.add(new Key(row.getLong(1), row.getInt(2)));
            });
        return legacyPending;
    }

    /**
     * 새 결과 하나를 옛 결과와 견줘 분류한다.
     *
     * @param fresh 새 결과
     * @param legacy 옛 결과. 없으면 비어 있다
     * @param legacyPending 옛 정산이 아직 대기 중이면 참
     * @return 분류
     */
    static Result classify(Fresh fresh, Verdict legacy, final boolean legacyPending) {
        if ("PENDING".equals(fresh.verdict().state())) {
            return Result.PENDING;
        }
        if (legacy == null) {
            return unmatchedResult(legacyPending);
        }
        if (legacy.equals(fresh.verdict())) {
            return Result.SAME;
        }
        if (TAINTS.contains(legacy.reason()) && !TAINTS.contains(fresh.verdict().reason())) {
            return Result.INTENDED_HOLE;
        }
        if (fresh.basis().atZone(SourceDates.KOREA).getHour() < MIDNIGHT_HOURS) {
            return Result.INTENDED_MIDNIGHT;
        }
        return Result.UNEXPLAINED;
    }

    private static Result unmatchedResult(final boolean legacyPending) {
        if (legacyPending) {
            return Result.PENDING;
        }
        return Result.NEW_ONLY;
    }

    private void record(Report report) {
        if (meters != null) {
            report.counts().forEach((result, count) -> meters.counter("salmonbus.pipeline.scoring.compared",
                "result", result.tag()).increment(count));
        }
        report.unexplained().stream().limit(WARNINGS_PER_RUN).forEach(difference -> LOGGER.warn(
            "event=pipeline_scoring_unexplained observationId={} target={} legacy={} scored={} newReason={}",
            difference.key().vehicleObservationId(), difference.key().targetStopOrder(), difference.legacy(),
            difference.scored(), difference.newReason()));
        if (report.unexplained().size() > WARNINGS_PER_RUN) {
            LOGGER.warn("event=pipeline_scoring_unexplained_more count={}",
                report.unexplained().size() - WARNINGS_PER_RUN);
        }
    }

    private static Fresh fresh(ResultSet row) throws SQLException {
        Key key = new Key(row.getLong("vehicle_observation_id"), row.getInt("target_stop_order"));
        String state = row.getString("state");
        String reason = row.getString("reason");
        Verdict verdict = freshVerdict(row, state, reason);
        return new Fresh(key, instant(row, "confirmed_at"), verdict, reason);
    }

    private static Verdict freshVerdict(ResultSet row, String state, String reason) throws SQLException {
        if ("SETTLED".equals(state)) {
            return new Verdict(state, null, Double.doubleToLongBits(row.getDouble("label_value")),
                row.getString("parameter_hash"), row.getInt("capacity"), nullableLong(row, "arrival_observation_id"),
                nullableLong(row, "last_observation_id"), nullableLong(row, "closure_observation_id"),
                nullableLong(row, "previous_last_observation_id"),
                nullableLong(row, "previous_closure_observation_id"));
        }
        if ("PENDING".equals(state)) {
            return Verdict.of(state, null);
        }
        return Verdict.of(state, ScoringReasons.legacyReason(reason).orElse("NONE:" + reason));
    }

    private static Verdict legacyVerdict(ResultSet row) throws SQLException {
        String state = row.getString("scoring_state");
        if (!"SETTLED".equals(state)) {
            return Verdict.of(state, row.getString("reason"));
        }
        return new Verdict(state, null, Double.doubleToLongBits(row.getDouble("label_value")),
            row.getString("parameter_hash"), (Integer) row.getObject("capacity"),
            nullableLong(row, "pre_observation_id"), nullableLong(row, "post_observation_id"),
            nullableLong(row, "closure_observation_id"), nullableLong(row, "previous_post_observation_id"),
            nullableLong(row, "previous_closure_observation_id"));
    }

    /** 대조 분류. 지표 태그 값이 이름의 소문자다. */
    public enum Result {
        SAME, INTENDED_HOLE, INTENDED_MIDNIGHT, UNEXPLAINED, LEGACY_ONLY, NEW_ONLY, PENDING;

        /** 지표 태그 값. */
        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private record Run(LocalDate sourceDate, long observationBatchId, Instant receivedAt) {
    }

    /**
     * 한 묶음에서 대조할 예보 실행.
     *
     * @param runs 고른 예보 실행. 읽은 순서 그대로다
     * @param reachedThrough 대조 상한보다 늦은 배치를 만나 멈췄으면 참
     */
    private record Selection(List<Run> runs, boolean reachedThrough) {
    }

    /**
     * 예측 하나의 키.
     *
     * @param vehicleObservationId 예측 기준 관측
     * @param targetStopOrder 목표 정류장
     */
    public record Key(long vehicleObservationId, int targetStopOrder) {
    }

    /**
     * 견주는 열. 채점됨이 아니면 상태와 사유만 채운다.
     *
     * @param state 상태
     * @param reason 옛 이름의 사유
     * @param labelBits 정답 값의 비트
     * @param hash 계수 해시
     * @param capacity 정원
     * @param pre 도착 전 관측
     * @param post 마지막 관측
     * @param closure 닫은 관측
     * @param previousPost 직전 통과의 마지막 관측
     * @param previousClosure 직전 통과를 닫은 관측
     */
    public record Verdict(
        String state,
        String reason,
        Long labelBits,
        String hash,
        Integer capacity,
        Long pre,
        Long post,
        Long closure,
        Long previousPost,
        Long previousClosure
    ) {
        static Verdict of(String state, String reason) {
            return new Verdict(state, reason, null, null, null, null, null, null, null, null);
        }
    }

    /**
     * 새 결과 하나.
     *
     * @param key 예측
     * @param basis 예측 기준 시각(착수 시각)
     * @param verdict 옛 이름으로 맞춘 견주는 열
     * @param reason 새 채점의 사유 그대로
     */
    record Fresh(Key key, Instant basis, Verdict verdict, String reason) {
    }

    /**
     * 설명되지 않는 차이 하나.
     *
     * @param key 예측
     * @param legacy 옛 결과
     * @param scored 새 결과(옛 이름으로 맞춘 것)
     * @param newReason 새 채점의 사유 그대로
     */
    public record Difference(Key key, Verdict legacy, Verdict scored, String newReason) {
    }

    /**
     * 한 회차의 대조 결과.
     *
     * @param batches 대조한 예보 실행 수
     * @param counts 분류별 예측 수
     * @param unexplained 설명되지 않는 차이
     */
    public record Report(int batches, Map<Result, Long> counts, List<Difference> unexplained) {
        public Report {
            counts = Map.copyOf(counts);
            unexplained = List.copyOf(unexplained);
        }

        static Report empty() {
            return new Report(0, new HashMap<>(), List.of());
        }

        Report plus(Report other) {
            Map<Result, Long> merged = new TreeMap<>(counts);
            other.counts.forEach((result, count) -> merged.merge(result, count, Long::sum));
            List<Difference> differences = new ArrayList<>(unexplained);
            differences.addAll(other.unexplained);
            return new Report(batches + other.batches, merged, differences);
        }

        /**
         * 분류 하나의 수.
         *
         * @param result 분류
         * @return 예측 수
         */
        public long count(Result result) {
            return counts.getOrDefault(result, 0L);
        }
    }
}
