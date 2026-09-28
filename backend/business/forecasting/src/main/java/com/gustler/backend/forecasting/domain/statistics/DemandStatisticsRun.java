package com.gustler.backend.forecasting.domain.statistics;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class DemandStatisticsRun {

    public static final int PAGE_SIZE = 128;
    private static final String NO_VEHICLE = "";
    public enum Phase { STALE, CLEAN, CAPTURE, ACCUMULATE, FOLD, REDUCE, PUBLISH, DONE }

    public record FoldCursor(String vehicleId, Instant arrivedHourStart, int targetStopOrder) {
        public FoldCursor {
            Objects.requireNonNull(vehicleId, "차량 ID가 필요하다");
            Objects.requireNonNull(arrivedHourStart, "도착 시간대가 필요하다");
        }
    }

    public record ReduceCursor(int stopOrder, TimeSlot timeSlot, LocalDate arrivalDate) {
        public ReduceCursor {
            Objects.requireNonNull(timeSlot, "시간대가 필요하다");
            Objects.requireNonNull(arrivalDate, "도착 날짜가 필요하다");
        }
    }

    private final long routeVersionId;
    private UUID runId;
    private long qualityRevision;
    private Phase phase;
    private Instant dataUntil;
    private long inputUntilId;
    private String vehicleCursor;
    private long inputCursor;
    private FoldCursor foldCursor;
    private ReduceCursor reduceCursor;
    private Instant completedAt;

    public DemandStatisticsRun(final long routeVersionId, final UUID runId, final long qualityRevision,
        final Phase phase, final Instant dataUntil, final long inputUntilId, final String vehicleCursor,
        final long inputCursor, final FoldCursor foldCursor, final ReduceCursor reduceCursor, final Instant completedAt) {
        this.routeVersionId = routeVersionId;
        this.runId = Objects.requireNonNull(runId, "계산 식별자가 필요하다");
        this.qualityRevision = qualityRevision;
        this.phase = Objects.requireNonNull(phase, "계산 단계가 필요하다");
        this.dataUntil = Objects.requireNonNull(dataUntil, "자료 기준 시각이 필요하다");
        this.inputUntilId = inputUntilId;
        this.vehicleCursor = Objects.requireNonNull(vehicleCursor, "차량 커서가 필요하다");
        this.inputCursor = inputCursor;
        this.foldCursor = foldCursor;
        this.reduceCursor = reduceCursor;
        this.completedAt = completedAt;
    }

    public static DemandStatisticsRun start(final long routeVersionId, final UUID runId, final long qualityRevision,
        final Instant now) {
        return new DemandStatisticsRun(routeVersionId, runId, qualityRevision, Phase.CLEAN, now, 0, NO_VEHICLE, 0,
            null, null, null);
    }

    public void restart(final UUID nextRunId, final long currentRevision, final Instant now) {
        runId = Objects.requireNonNull(nextRunId, "계산 식별자가 필요하다");
        qualityRevision = currentRevision;
        phase = Phase.CLEAN;
        dataUntil = now.isAfter(dataUntil) ? now : dataUntil;
        inputUntilId = 0;
        vehicleCursor = NO_VEHICLE;
        inputCursor = 0;
        foldCursor = null;
        reduceCursor = null;
    }

    public boolean isUpToDate(final long currentRevision, final Instant now, final Duration refreshInterval) {
        return phase == Phase.DONE && qualityRevision == currentRevision && completedAt != null
            && now.isBefore(completedAt.plus(refreshInterval));
    }

    public boolean requiresRestart(final long currentRevision) {
        return phase == Phase.DONE || phase == Phase.STALE || qualityRevision != currentRevision;
    }

    public void markStale() {
        phase = Phase.STALE;
    }

    public void cleaned() {
        requirePhase(Phase.CLEAN);
        phase = Phase.CAPTURE;
    }

    public Instant captureUntil(final Instant now) {
        return now.isAfter(dataUntil) ? now : dataUntil;
    }

    public void captured(final Instant fixedDataUntil, final long lastInputId) {
        requirePhase(Phase.CAPTURE);
        dataUntil = fixedDataUntil;
        inputUntilId = lastInputId;
        phase = Phase.ACCUMULATE;
    }

    public long accumulationStartAfter(final String vehicleId) {
        return vehicleId.equals(vehicleCursor) ? inputCursor : 0;
    }

    public void accumulated(final String vehicleId, final long nextInputId) {
        requirePhase(Phase.ACCUMULATE);
        vehicleCursor = vehicleId;
        inputCursor = nextInputId;
    }

    public void accumulationFinished() {
        requirePhase(Phase.ACCUMULATE);
        vehicleCursor = NO_VEHICLE;
        inputCursor = 0;
        phase = Phase.FOLD;
    }

    public void folded(final FoldCursor last) {
        requirePhase(Phase.FOLD);
        foldCursor = Objects.requireNonNull(last, "마지막으로 접은 합계가 필요하다");
    }

    public void foldFinished() {
        requirePhase(Phase.FOLD);
        phase = Phase.REDUCE;
    }

    public void reduced(final ReduceCursor last) {
        requirePhase(Phase.REDUCE);
        reduceCursor = Objects.requireNonNull(last, "마지막으로 줄인 날짜 합계가 필요하다");
    }

    public void reduceFinished() {
        requirePhase(Phase.REDUCE);
        phase = Phase.PUBLISH;
    }

    public void published(final Instant computedAt) {
        requirePhase(Phase.PUBLISH);
        phase = Phase.DONE;
        completedAt = Objects.requireNonNull(computedAt, "계산 완료 시각이 필요하다");
    }

    private void requirePhase(final Phase expected) {
        if (phase != expected) {
            throw new IllegalStateException("통계 계산 단계가 %s가 아니다: %s".formatted(expected, phase));
        }
    }

    public long routeVersionId() { return routeVersionId; }
    public UUID runId() { return runId; }
    public long qualityRevision() { return qualityRevision; }
    public Phase phase() { return phase; }
    public Instant dataUntil() { return dataUntil; }
    public long inputUntilId() { return inputUntilId; }
    public String vehicleCursor() { return vehicleCursor; }
    public long inputCursor() { return inputCursor; }
    public Optional<FoldCursor> foldCursor() { return Optional.ofNullable(foldCursor); }
    public Optional<ReduceCursor> reduceCursor() { return Optional.ofNullable(reduceCursor); }
    public Optional<Instant> completedAt() { return Optional.ofNullable(completedAt); }
}
