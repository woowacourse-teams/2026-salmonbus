package com.gustler.backend.forecasting.application.forecast;

import com.gustler.backend.forecasting.api.forecast.ForecastPublished;
import com.gustler.backend.forecasting.api.forecast.PassClosed;
import com.gustler.backend.forecasting.domain.forecast.SourceDates;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 다시 뜰 때 저장된 예보 기록과 통과 기록을 사건 순서로 다시 흘려 노선의 채점 상태를 만든다.
 *
 * <p><b>예보 범위</b>는 고정 시간이 아니라 저장된 자료로 정한다. 채점이 그 노선을 저장까지 마친 사건 시각을 두 가지로 찾아 늦은
 * 쪽을 쓴다. 정답이 저장된 통과 가운데 가장 늦게 닫힌 것의 닫힌 시각, 그리고 미해결 줄이 저장된 예측 가운데 가장 늦은 것의 착수
 * 시각이다. 채점 단계는 노선의 사건을 순서대로 처리하고 결과도 그 순서로 저장되므로({@link ScoringWriter}), 그 시각보다 마감이
 * 이른 예측은 이미 정답이나 미해결 줄로 닫혀 저장돼 있다. 그래서 그 시각에서 {@code forecastWindow}(기본 2시간 10분, 마감
 * 2시간에 수신 뒤 착수까지의 지연 여유를 더한 값)만큼 앞의 수신 시각부터 예보를 읽으면, 내려가 있던 시간이나 되감기가 실패하던
 * 시간이 얼마든 그 사이 닫히지 못한 예측을 모두 다시 받는다. 줄을 저장한 시각(벽시계)을 기준으로 삼지 않는 것은, 오래 내려가 있다가
 * 되감으며 쓴 줄의 시각이 그 노선의 사건 시각보다 훨씬 늦어서 그 직후 다시 내려가면 범위가 모자라기 때문이다.
 * <ul>
 *   <li>범위의 시작은 되감기 시각에서 {@code forecastWindow}보다 늦지 않다. 중단이 없으면 지금까지와 같은 범위다.</li>
 *   <li>범위의 시작은 되감기 시각에서 {@code maxForecastWindow}(기본 26시간)보다 이르지 않다. 그보다 오래 닫히지 못한 예측은 다시
 *       읽지 않으며 정답도 미해결 줄도 없이 남는다.</li>
 *   <li>상한 안에 저장된 정답도 미해결 줄도 없으면(처음 켰거나 상한보다 오래 멈춘 노선) 상한까지 읽는다.</li>
 * </ul>
 * 이미 닫힌 예측을 다시 흘려도 같은 결과가 나오고, 저장소가 이미 있는 키를 건너뛰므로 다시 나온 결과는 그대로 돌려준다.
 *
 * <p><b>통과 범위</b>는 예보 범위의 시작보다 {@code passWindow - forecastWindow}(기본 23시간 50분) 앞에서 시작한다. 중단이 없으면
 * 닫힌 시각이 최근 {@code passWindow}(기본 26시간) 안인 통과다. 새로 정답을 낼 수 있는 통과는 예보 범위 안에서 닫힌 것이고, 그
 * 직전 통과(같은 정류장·같은 출발 날짜)가 되감은 상태에 있어야 연속 실행과 같은 정답이 나오기 때문이다.
 *
 * <p><b>수집 구멍과 관측 줄 수</b>는 수집 사건이 저장되지 않으므로, 예보 실행 줄에 함께 적어 둔 수집 사실로 되살린다
 * ({@link ScoringStage#restore}). 예보 범위가 시작하는 한국 날짜의 00시({@value #INTAKE_MARGIN_MINUTES}분 앞)부터 읽는다.
 * 채점 단계는 예측 기준 시각의 날짜 00시부터의 구멍과 줄 수를 보기 때문이다.
 *
 * <p>세 흐름은 (배치 착수 시각, 배치 번호) 순서로 합치고 같은 배치에서는 수집 사실, 통과, 예보 순서로 흘린다.
 *
 * <p>이관 앱은 여기서 다시 읽는 가장 긴 범위(수집 사실: 예보 범위의 상한 + 하루 + {@value #INTAKE_MARGIN_MINUTES}분)를
 * {@code PartitionExportSettings.LONGEST_REPLAY}에 적어 두고 그 범위 안의 날짜 파티션을 지우지 않는다. 범위의 상한을 늘리면 그 값도
 * 함께 늘린다.
 */
public final class ScoringReplay {
    /** 예보 기록을 되감는 가장 짧은 범위. 마감(착수 시각 + 2시간)에 수신 뒤 착수까지의 지연 여유를 더한 값이다. */
    public static final Duration DEFAULT_FORECAST_WINDOW = ScoringStage.IDLE_EXPIRY;
    /** 중단이 없을 때 통과 기록을 되감는 범위. */
    public static final Duration DEFAULT_PASS_WINDOW = Duration.ofHours(26);
    /** 예보 기록을 되감는 가장 긴 범위. */
    public static final Duration DEFAULT_MAX_FORECAST_WINDOW = Duration.ofHours(26);

    static final long INTAKE_MARGIN_MINUTES = 10;
    private static final Duration INTAKE_MARGIN = Duration.ofMinutes(INTAKE_MARGIN_MINUTES);
    private static final Comparator<Event> ORDER = Comparator.comparing(Event::at)
        .thenComparingLong(Event::batchId)
        .thenComparingInt(Event::rank);

    private final ForecastRecordStore forecasts;
    private final StopPassStore passes;
    private final Duration forecastWindow;
    private final Duration passLead;
    private final Duration maxForecastWindow;

    /**
     * 기본 범위로 되감기를 만든다.
     *
     * @param forecasts 예보 기록 저장소
     * @param passes 통과 기록 저장소
     */
    public ScoringReplay(ForecastRecordStore forecasts, StopPassStore passes) {
        this(forecasts, passes, DEFAULT_FORECAST_WINDOW, DEFAULT_PASS_WINDOW, DEFAULT_MAX_FORECAST_WINDOW);
    }

    /**
     * 되감기를 만든다.
     *
     * @param forecasts 예보 기록 저장소
     * @param passes 통과 기록 저장소
     * @param forecastWindow 예보 기록을 되감는 가장 짧은 범위. 0보다 길다
     * @param passWindow 중단이 없을 때 통과 기록을 되감는 범위. 예보의 가장 짧은 범위보다 짧지 않다
     * @param maxForecastWindow 예보 기록을 되감는 가장 긴 범위. 가장 짧은 범위보다 짧지 않다
     */
    public ScoringReplay(
        ForecastRecordStore forecasts,
        StopPassStore passes,
        Duration forecastWindow,
        Duration passWindow,
        Duration maxForecastWindow
    ) {
        this.forecasts = Objects.requireNonNull(forecasts, "예보 기록 저장소가 필요하다");
        this.passes = Objects.requireNonNull(passes, "통과 기록 저장소가 필요하다");
        if (isNotPositive(forecastWindow)) {
            throw new IllegalArgumentException("예보 범위는 0보다 길어야 한다: " + forecastWindow);
        }
        if (passWindow == null || passWindow.compareTo(forecastWindow) < 0) {
            throw new IllegalArgumentException("통과 범위는 예보 범위보다 짧을 수 없다: " + passWindow);
        }
        if (maxForecastWindow == null || maxForecastWindow.compareTo(forecastWindow) < 0) {
            throw new IllegalArgumentException("예보 범위의 상한은 가장 짧은 범위보다 짧을 수 없다: " + maxForecastWindow);
        }
        this.forecastWindow = forecastWindow;
        this.passLead = passWindow.minus(forecastWindow);
        this.maxForecastWindow = maxForecastWindow;
    }

    private static boolean isNotPositive(Duration window) {
        return window == null || window.isNegative() || window.isZero();
    }

    /** 예보 기록을 되감는 가장 긴 범위. 이보다 최근에 예보가 있던 노선은 닫히지 못한 예측이 남아 있을 수 있다. */
    public Duration maxForecastWindow() {
        return maxForecastWindow;
    }

    /**
     * 노선의 상태를 버리고 저장된 기록으로 다시 만든다.
     *
     * @param stage 채점 단계
     * @param routeId 노선
     * @param now 되감기 기준 시각. 이 시각 이전에 수신·닫힌 기록만 읽는다
     * @return 다시 흘리며 나온 결과
     */
    public ScoringResult replay(ScoringStage stage, final long routeId, Instant now) {
        stage.discard(routeId);
        Instant forecastFrom = forecastFrom(routeId, now);
        List<Event> events = new ArrayList<>();
        for (RecordedBatch batch : forecasts.findRecordedBatchesBetween(routeId, intakeFrom(forecastFrom), now)) {
            events.add(new Event(batch.confirmedAt(), batch.observationBatchId(), 0, batch, null, null));
        }
        for (PassClosed pass : passes.findClosedBetween(routeId, forecastFrom.minus(passLead), now)) {
            events.add(new Event(pass.closedAt(), pass.closureBatchId(), 1, null, pass, null));
        }
        for (ForecastPublished published : forecasts.findPublishedBetween(routeId, forecastFrom, now)) {
            events.add(new Event(published.confirmedAt(), published.observationBatchId(), 2, null, null, published));
        }
        events.sort(ORDER);
        ScoringResult result = ScoringResult.empty();
        for (Event event : events) {
            result = result.plus(accept(stage, event));
        }
        return result;
    }

    /** 예보를 다시 읽기 시작할 수신 시각이다. 채점이 저장까지 마친 사건 시각에서 가장 짧은 범위만큼 앞이고, 상한보다 이르지 않다. */
    private Instant forecastFrom(final long routeId, Instant now) {
        Instant earliest = now.minus(maxForecastWindow);
        Optional<Instant> scoredThrough = Stream.of(passes.findLastScoredClosedAt(routeId, earliest, now),
                forecasts.findLastUnresolvedConfirmedAt(routeId, earliest, now))
            .flatMap(Optional::stream)
            .max(Comparator.naturalOrder());
        if (scoredThrough.isEmpty()) {
            return earliest;
        }
        Instant from = scoredThrough.get().minus(forecastWindow);
        if (from.isBefore(earliest)) {
            return earliest;
        }
        return from;
    }

    private static Instant intakeFrom(Instant forecastFrom) {
        return SourceDates.of(forecastFrom).atStartOfDay(SourceDates.KOREA).toInstant().minus(INTAKE_MARGIN);
    }

    private static ScoringResult accept(ScoringStage stage, Event event) {
        if (event.batch() != null) {
            return stage.restore(event.batch());
        }
        if (event.pass() != null) {
            return stage.accept(event.pass());
        }
        return stage.accept(event.published());
    }

    private record Event(
        Instant at,
        long batchId,
        int rank,
        RecordedBatch batch,
        PassClosed pass,
        ForecastPublished published
    ) {
    }
}
