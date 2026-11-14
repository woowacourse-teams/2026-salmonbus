package com.gustler.backend.forecasting.application.pipeline;

import com.gustler.backend.forecasting.api.pipeline.ForecastPublished;
import com.gustler.backend.forecasting.api.pipeline.PassClosed;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 다시 뜰 때 저장된 예보 기록과 통과 기록을 사건 순서로 다시 흘려 노선의 채점 상태를 만든다.
 *
 * <p>예보 기록은 수신 시각이 최근 {@code forecastWindow}(기본 2시간 10분) 안인 것, 통과 기록은 닫힌 시각이 최근
 * {@code passWindow}(기본 26시간) 안인 것을 읽는다. 예보 범위가 마감(착수 시각 + 2시간)보다 긴 것은 수신 뒤 늦게 착수한
 * 배치의 예측이 범위 밖으로 빠져 결과 없이 사라지지 않게 하려는 것이다. 이미 닫힌 예측을 다시 흘려도 같은 결과가 나온다. 자정 직후의
 * 통과는 어제 출발 날짜의 통과를 직전 통과로 써야 하므로 통과 범위가 예보 범위보다 길다. 두 흐름은 (배치 확정 시각, 배치 번호)
 * 순서로 합치고 같은 배치에서는 통과를 먼저 흘린다. 다시 흘리며 나온 결과는 이미 저장된 것과 키가 같으면 저장소가 건너뛰므로
 * 그대로 돌려준다.
 *
 * <p>수집 사건({@code ObservationBatchSeen})은 저장되지 않아 되감기에서 수집 구멍과 하루 관측 수를 되살리지 못한다. 되감은 뒤에
 * 그날 앞선 구멍으로 닫혔어야 할 예측이 채점될 수 있다.
 */
public final class ScoringReplay {
    /** 예보 기록을 되감는 기본 범위. */
    public static final Duration DEFAULT_FORECAST_WINDOW = ScoringStage.IDLE_EXPIRY;
    /** 통과 기록을 되감는 기본 범위. */
    public static final Duration DEFAULT_PASS_WINDOW = Duration.ofHours(26);

    private static final Comparator<Event> ORDER = Comparator.comparing(Event::at)
        .thenComparingLong(Event::batchId)
        .thenComparingInt(Event::rank);

    private final ForecastRecordStore forecasts;
    private final StopPassStore passes;
    private final Duration forecastWindow;
    private final Duration passWindow;

    /**
     * 기본 범위로 되감기를 만든다.
     *
     * @param forecasts 예보 기록 저장소
     * @param passes 통과 기록 저장소
     */
    public ScoringReplay(ForecastRecordStore forecasts, StopPassStore passes) {
        this(forecasts, passes, DEFAULT_FORECAST_WINDOW, DEFAULT_PASS_WINDOW);
    }

    /**
     * 되감기를 만든다.
     *
     * @param forecasts 예보 기록 저장소
     * @param passes 통과 기록 저장소
     * @param forecastWindow 예보 기록을 되감는 범위. 0보다 길다
     * @param passWindow 통과 기록을 되감는 범위. 예보 범위보다 짧지 않다
     */
    public ScoringReplay(ForecastRecordStore forecasts, StopPassStore passes, Duration forecastWindow,
        Duration passWindow) {
        this.forecasts = Objects.requireNonNull(forecasts, "예보 기록 저장소가 필요하다");
        this.passes = Objects.requireNonNull(passes, "통과 기록 저장소가 필요하다");
        if (forecastWindow == null || forecastWindow.isNegative() || forecastWindow.isZero()) {
            throw new IllegalArgumentException("예보 범위는 0보다 길어야 한다: " + forecastWindow);
        }
        if (passWindow == null || passWindow.compareTo(forecastWindow) < 0) {
            throw new IllegalArgumentException("통과 범위는 예보 범위보다 짧을 수 없다: " + passWindow);
        }
        this.forecastWindow = forecastWindow;
        this.passWindow = passWindow;
    }

    /**
     * 노선의 상태를 버리고 저장된 기록으로 다시 만든다.
     *
     * @param stage 채점 단계
     * @param routeId 노선
     * @param now 되감기 기준 시각. 이 시각 이전에 수신·닫힌 기록만 읽는다
     * @return 다시 흘리며 나온 결과
     */
    public ScoringResult replay(ScoringStage stage, long routeId, Instant now) {
        stage.discard(routeId);
        List<Event> events = new ArrayList<>();
        for (PassClosed pass : passes.findClosedBetween(routeId, now.minus(passWindow), now)) {
            events.add(new Event(pass.closedAt(), pass.closureBatchId(), 0, pass, null));
        }
        for (ForecastPublished published : forecasts.findPublishedBetween(routeId, now.minus(forecastWindow), now)) {
            events.add(new Event(published.confirmedAt(), published.observationBatchId(), 1, null, published));
        }
        events.sort(ORDER);
        ScoringResult result = ScoringResult.empty();
        for (Event event : events) {
            result = result.plus(event.pass() != null ? stage.accept(event.pass()) : stage.accept(event.published()));
        }
        return result;
    }

    private record Event(Instant at, long batchId, int rank, PassClosed pass, ForecastPublished published) {
    }
}
