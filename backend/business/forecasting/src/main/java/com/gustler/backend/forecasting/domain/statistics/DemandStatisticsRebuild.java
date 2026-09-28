package com.gustler.backend.forecasting.domain.statistics;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class DemandStatisticsRebuild {

    public static final int PAGE_SIZE = 128;
    public static final int BATCH_GROUP_SIZE = 32;

    private final long routeVersionId;
    private final RebuildScope scope;
    private final UUID requestId;
    private final long qualityRevision;
    private final Instant dataUntil;
    private final long inputUntilId;
    private final long observationUntilId;
    private long cursorId;
    private RebuildPhase phase;
    private RebuildScanWindow scan;

    public DemandStatisticsRebuild(final long routeVersionId, final RebuildScope scope, final UUID requestId,
        final long qualityRevision, final Instant dataUntil, final long inputUntilId, final long observationUntilId,
        final long cursorId, final RebuildPhase phase, final RebuildScanWindow scan) {
        this.routeVersionId = routeVersionId;
        this.scope = Objects.requireNonNull(scope, "정정 범위가 필요하다");
        this.requestId = Objects.requireNonNull(requestId, "정정 요청 식별자가 필요하다");
        this.qualityRevision = qualityRevision;
        this.dataUntil = Objects.requireNonNull(dataUntil, "자료 기준 시각이 필요하다");
        this.inputUntilId = inputUntilId;
        this.observationUntilId = observationUntilId;
        this.cursorId = cursorId;
        this.phase = Objects.requireNonNull(phase, "정정 단계가 필요하다");
        this.scan = scan;
    }

    public static DemandStatisticsRebuild start(final long routeVersionId, final RebuildScope scope,
        final UUID requestId, final long qualityRevision, final Instant dataUntil, final long inputUntilId,
        final long observationUntilId, final long batchUntilId) {
        return new DemandStatisticsRebuild(routeVersionId, scope, requestId, qualityRevision, dataUntil, inputUntilId,
            observationUntilId, 0, RebuildPhase.SCAN, RebuildScanWindow.startingAt(batchUntilId));
    }

    // 차량별 변경은 해당 요청 UUID로 판단한다. 전체 계산은 모든 차량의 품질 변경에 영향을 받는다.
    public boolean serves(final UUID currentRequestId, final long currentRevision) {
        return requestId.equals(currentRequestId) && (!scope.isWholeRoute() || qualityRevision == currentRevision);
    }

    public boolean scansByObservationId() {
        return scan == null;
    }

    public void scannedObservations(final List<Long> observationIds) {
        requirePhase(RebuildPhase.SCAN);
        if (observationIds.size() < PAGE_SIZE) {
            move(RebuildPhase.CLEAR, 0);
            return;
        }
        move(RebuildPhase.SCAN, observationIds.getLast());
    }

    public void groupOpened(final Instant endAt, final long endBatchId) {
        requirePhase(RebuildPhase.SCAN);
        scan = requireScan().openGroup(endAt, endBatchId);
    }

    public void noGroupLeft() {
        requirePhase(RebuildPhase.SCAN);
        move(RebuildPhase.CLEAR, 0);
    }

    public void scannedGroup(final List<Long> observationIds) {
        requirePhase(RebuildPhase.SCAN);
        if (observationIds.size() < PAGE_SIZE) {
            scan = requireScan().closeGroup();
            move(RebuildPhase.SCAN, 0);
            return;
        }
        move(RebuildPhase.SCAN, observationIds.getLast());
    }

    public void cleared(final int removed) {
        requirePhase(RebuildPhase.CLEAR);
        if (removed == 0) {
            move(RebuildPhase.COPY, 0);
        }
    }

    public void copied(final int copied, final long lastTotalId) {
        requirePhase(RebuildPhase.COPY);
        if (copied < PAGE_SIZE) {
            move(RebuildPhase.ACK, 0);
            return;
        }
        move(RebuildPhase.COPY, lastTotalId);
    }

    public void acknowledged(final int visited, final long lastSampleId) {
        requirePhase(RebuildPhase.ACK);
        if (visited < PAGE_SIZE) {
            move(RebuildPhase.CLEAN, 0);
            return;
        }
        move(RebuildPhase.ACK, lastSampleId);
    }

    public boolean cleanedUp(final int removed) {
        requirePhase(RebuildPhase.CLEAN);
        return removed == 0;
    }

    private RebuildScanWindow requireScan() {
        if (scan == null) {
            throw new IllegalStateException("관측 ID 순서로 시작한 정정에는 묶음 범위가 없다");
        }
        return scan;
    }

    private void move(final RebuildPhase next, final long nextCursorId) {
        phase = next;
        cursorId = nextCursorId;
    }

    private void requirePhase(final RebuildPhase expected) {
        if (phase != expected) {
            throw new IllegalStateException("통계 정정 단계가 %s가 아니다: %s".formatted(expected, phase));
        }
    }

    public long routeVersionId() { return routeVersionId; }
    public RebuildScope scope() { return scope; }
    public UUID requestId() { return requestId; }
    public long qualityRevision() { return qualityRevision; }
    public Instant dataUntil() { return dataUntil; }
    public long inputUntilId() { return inputUntilId; }
    public long observationUntilId() { return observationUntilId; }
    public long cursorId() { return cursorId; }
    public RebuildPhase phase() { return phase; }
    public Optional<RebuildScanWindow> scan() { return Optional.ofNullable(scan); }
}
